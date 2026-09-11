# hop-python-plugin

Apache Hop **2.19.0** transform plugin embedding **GraalPy 25.3.4.1**. Build with Java 21; CI covers regular Temurin/OpenJDK 21 and 25 on Linux, macOS ARM64 and Windows. The plugin includes its Python runtime; an external CPython interpreter is not supported.

## Build and install

```bash
mvn clean verify
python3 scripts/verify-package.py
unzip assemblies/assemblies-transform-graalpy/target/hop-transform-graalpy-0.1.0-SNAPSHOT.zip -d "$HOP_HOME"
```

The install root is `plugins/transforms/graalpy`. `scripts/dev-sync-hop-plugin.sh "$HOP_HOME"` remains available for local development; it builds without tests and replaces that plugin directory.

Modules:

- `hop-transform-graalpy`: metadata, dialog, runtime and tests.
- `assemblies/assemblies-transform-graalpy`: install ZIP.

CI publishes only after verification, pure-Python environment tests, SWT smoke tests and installed-Hop scenarios pass. The verified ZIP is published on `main` as `ch.so.agi:hop-transform-graalpy:0.1.0-SNAPSHOT`; pull requests do not publish.

## Python row contract

Scripts must define `process(row, ctx)`. Each transform copy has its own context and state.

```python
def process(row, ctx):
    if not row['active']:
        return None                 # filter the input row
    return {'name': row['name'].upper()}
```

`RETURN_ONE` accepts a dictionary of **changes**, or `None`. `return {}` passes through one row unchanged, including when the output-field table is empty.

- Declare every returned field in **Output fields**.
- Existing fields to change use **Replace existing = Yes**.
- New appended fields use **Replace existing = No**.
- An omitted replacement retains its original value; an omitted new field is null.
- Explicit `None` sets the output field to null.
- An undeclared output field is an error. Returning the whole input dictionary is valid only when all its keys are declared.

`EMIT_MANY` accepts zero or more calls to `ctx.emit(mapping)` and requires `process()` to return `None`:

```python
def process(row, ctx):
    for part in row['text'].split(';'):
        ctx.emit({'part': part})
```

Each output gets a separate Java row array and an immediate snapshot of converted output values. Outputs are buffered **per input row**. `ctx.skip()` or `ctx.reject()` discards that input's buffered outputs. There is no streaming/generator contract, and no rollback of rows from earlier inputs.

## Inputs, parameters and lifecycle

**Inputs / Parameters** selects `ALL` or `SELECTED`. In selected mode, Python sees only the listed fields. An empty selection is valid. Unselected fields stay on the Hop side, so unsupported types can pass through unchanged. Missing selected fields are configuration errors.

Parameters have unique names and string values. Hop variables are resolved once when the session starts; values are passed separately from Python source.

```python
def setup(ctx):
    ctx.state['minimum'] = float(ctx.parameters['MINIMUM'])
    ctx.state['accepted'] = 0

def process(row, ctx):
    if row['height'] < ctx.state['minimum']:
        return None
    ctx.state['accepted'] += 1
    return {}

def close(ctx):
    ctx.log.info(f"Accepted {ctx.state['accepted']} rows")
```

`setup(ctx)` and `close(ctx)` are optional and must return `None`. They cannot emit, skip or reject rows. Setup runs once before processing, including on empty input. Close runs once before normal context disposal, including after a failure when the context remains usable. Forced cancellation cannot run Python cleanup. There is no `finish()` hook or hook-generated output.

Available context properties:

| Property | Meaning |
|---|---|
| `ctx.parameters` | Read-only mapping of resolved string parameters |
| `ctx.state` | Mutable Python dictionary private to this copy/session |
| `ctx.input_fields` | Read-only metadata for fields exposed to Python |
| `ctx.fields` | Alias of `ctx.input_fields` |
| `ctx.output_fields` | Read-only complete output schema |
| `ctx.transform_name`, `ctx.copy_nr` | Transform identity |
| `ctx.log.info/warn/error(message)` | Bounded Hop logging |
| `ctx.geometry` | Optional geometry constructors |

Field metadata contains `name`, `type`, `length` and `precision`. Global Python variables also remain local to the session. They are not pipeline-wide state.

## Inline and external scripts

**Script source** is `INLINE` or `FILE`.

- Inline code remains stored in pipeline metadata.
- File paths support Hop variables. Relative paths resolve against `${PROJECT_HOME}`; without it, use an absolute path.
- A file is loaded once per session as UTF-8. Its absolute path and SHA-256 are logged. Editing it does not change an already running session.
- There is no substitution of variables in Python source and no automatic addition of the script directory to the Python import path. Install reusable importable modules in the configured venv.

The editor's **Load**, **Save** and **Save as** remain import/export conveniences. Their last-used path is local to the dialog session and is independent of the persisted runtime file path. Existing saved scripts are never rewritten by migration.

## External GraalPy environments

Enable **External GraalPy environment** and supply a GraalPy-created venv. Provision it and install packages outside Hop; the dialog does not run pip.

Before loading the user script, Hop probes the selected interpreter using a fixed isolated command with a ten-second timeout. It checks `sys.implementation`, the language version and the distribution's `release` metadata against GraalPy 25.3.4.1, then verifies the embedded context's actual venv prefix. This matters because the 25.3.4.1 distribution reports only 25.3.4 in `sys.graalpy_version_info`. Missing distribution metadata, CPython environments and mismatched versions are rejected explicitly.

The default embedded context denies file/network I/O, arbitrary Java class access, process creation, thread creation and native extensions. External environments enable filesystem/network I/O for trusted Python code. **Native access** is a separate experimental opt-in for new configurations. Native extensions can bypass JVM/Truffle restrictions and are not guaranteed to work across platforms or multiple contexts. This release guarantees the tested pure-Python environment path, not arbitrary package compatibility.

## Cancellation and limits

Hop Stop and preview Stop cancel the active context. A cancelled context is never reused. Runtime diagnostics distinguish script loading, setup, row processing and close, and include script location, transform/copy and input row number where applicable.

New transforms default to:

| Limit | Default |
|---|---:|
| Seconds per phase or input row | 60 |
| Buffered emitted rows per input | 10,000 |
| Python log bytes per session | 1 MiB |

`0` disables an individual limit. Processing time includes Python execution and conversion; downstream Hop backpressure is outside this timer. Output-limit and timeout failures abort the transform. Log overflow produces one suppression notice. `ctx.log`, stdout and stderr share the log budget.

Cancellation is not hard process isolation: blocking Java calls and native code may not terminate promptly, and row/log limits are not a general memory quota. Independent native worker processes are outside this version.

## Hop error handling

```python
def process(row, ctx):
    if row['height'] is None:
        ctx.reject(code='MISSING_HEIGHT', message='Height is required', field='height')
    return {}
```

Configure a normal Hop error hop to route rejections. `reject()` forwards the **original input row**, including excluded fields, with code/message/field information. Without an error hop it fails the transform. Hop's error thresholds apply normally.

`skip()` is ordinary filtering. `abort(message)` fails the transform deliberately. Syntax errors, uncaught Python exceptions, conversion errors and runtime limits remain fatal; they are not automatically routed as data errors. Cleanup failures do not replace the primary failure.

## Geometry and Binary

Install the separate **hop-geometry-type 0.2** plugin to use Geometry fields. The adapter uses its registered classloader and curve-aware codecs. The Python ZIP contains neither another geometry plugin nor another JTS runtime. Ordinary scripts work without the geometry plugin; unselected Geometry fields can pass through unchanged.

```python
def process(row, ctx):
    geom = row['geom']
    return {
        'area': geom.area,
        'center': geom.centroid(),
        'buffer': geom.buffer(5),
    }
```

Declare `area` as Number and `center`/`buffer` as Geometry. Geometry outputs accept only wrappers produced by this session or `None`; strings are not implicitly parsed.

Properties: `area`, `length`, `is_valid`, `is_empty`, `geom_type`, `srid`.

Methods and constructors:

```python
geom.buffer(distance)       # 8 segments per quadrant; new geometry, same SRID
geom.centroid()             # new geometry, same SRID
geom.to_wkt()               # WKT without SRID
geom.to_wkb()               # bytes; EWKB includes SRID when set
ctx.geometry.from_wkt(text, srid=0)
ctx.geometry.from_wkb(data, srid=None)  # preserve embedded SRID unless explicitly overridden
```

Wrappers are read-only. Python cannot access the underlying Java geometry. Imports and output snapshots are independent copies; `None` and empty geometries remain distinct.

Area, length, validity, buffer and centroid require **linear XY input**. Calculations are planar in coordinate units, without reprojection or geodesic interpretation. Curves and declared Z/M sequences—including empty sequences—are rejected for these calculations; they are not silently flattened. Some legacy JTS producers allocate XYZ sequences even for XY-looking coordinates; such fields must be explicitly normalized upstream before calculation.

WKB/EWKB preserves the curve and Z/M types supported by the installed codecs. WKT output can retain curves; WKT input supports linear types including Z/M. Use EWKB to construct curved geometries. An unknown SRID remains `0`.

Hop Binary is exposed as copied Python `bytes`. Binary output accepts `bytes` or `bytearray` and snapshots the content.

## Manual test preview

**Test…** opens a session-local preview. Define an input schema, choose the number of rows and apply the schema to edit cells. Each field has an explicit null selector; a blank string is not automatically null. Binary and Geometry input use hex WKB/EWKB. Geometry output includes readable WKT and EWKB.

Results, rejected rows, logs and errors appear separately. Every run uses a fresh production runtime session with the current dialog configuration. The preview does not run upstream transforms, save example data into the pipeline or infer/alter the output schema.

Preview caps are 100 input rows, 1,000 total output rows, 1 MiB logs and 30 seconds total runtime. Stricter transform limits still apply. Execution runs off the SWT thread; Stop and closing the window request cancellation. The pipeline configuration is applied only with the main dialog's OK button.

## Compatibility

New metadata stores `configurationVersion=2`. Loading XML without that field retains the old behavior: inline source, all input fields, disabled limits and the previous native permission for enabled external environments. Output scripts and field declarations remain unchanged. Saving writes the migrated configuration explicitly. New dialogs start with the defaults above.

Date/Timestamp semantics are unchanged in this release: system-zone conversion and millisecond-limited Java Date output. Nanosecond-preserving timestamp conversion is a separate change.

## Verification

```bash
# All local tests and install ZIP
mvn clean verify
python3 scripts/verify-package.py

# Reproducible native-distribution venv with a pinned pure-Python package
python3 scripts/setup-test-venv.py --directory target/test-python
mvn -pl hop-transform-graalpy -am test \
  -Dgraalpy.test.venv="$PWD/target/test-python/venv" \
  -Dgraalpy.test.cpython="$PWD/target/test-python/cpython-venv" \
  -Dgraalpy.test.pure.package=packaging

# Real SWT interaction; use xvfb-run -a on headless Linux
mvn -pl hop-transform-graalpy -am test \
  -Dtest=PythonDialogSmokeTest -Dgraalpy.test.ui=true \
  -Dsurefire.failIfNoSpecifiedTests=false

# Use a disposable, freshly extracted Hop installation
python3 scripts/run-e2e.py --hop-home /path/to/disposable/hop \
  --plugin-zip assemblies/assemblies-transform-graalpy/target/hop-transform-graalpy-0.1.0-SNAPSHOT.zip
# Add --geometry-plugin-zip /path/to/hop-geometry-type-plugin-0.2.0-SNAPSHOT.zip
```

Environment and SWT tests are opt-in locally and required in their dedicated CI jobs. Native-package tests remain optional. Cancellation probes run in disposable JVMs with external process deadlines. Installed tests cover pure filters, independent emitted rows, file scripts/parameters, error hops, empty-input hooks and, with Geometry installed, geometry handoff to another transform.
