# hop-python-plugin

Apache Hop `2.17.0` transform plugin that executes inline Python scripts with GraalPy.

## Modules

- `./hop-transform-graalpy`
  - Main transform implementation, UI dialog, resources and tests.
- `./assemblies/assemblies-transform-graalpy`
  - Install ZIP assembly under `plugins/transforms/graalpy`.

## Build

Full build including tests and assembly:

```bash
mvn clean verify
```

Fast local plugin build without tests:

```bash
mvn -pl hop-transform-graalpy -am -DskipTests package
```

Build prerequisites:

- Java 17 compatible toolchain (`maven.compiler.release=17`)
- Regular JDK 17, not a GraalVM JDK

GraalPy `25.0.2` conflicts with GraalVM JDK bundled modules during plugin tests, so use Temurin/OpenJDK 17.

## Install in Hop

### Option A: Manual ZIP install

1. Build the install ZIP:

```bash
mvn -pl assemblies/assemblies-transform-graalpy -am package
```

2. Extract it into your Hop home:

```bash
unzip -o ./assemblies/assemblies-transform-graalpy/target/hop-transform-graalpy-0.1.0-SNAPSHOT.zip -d "$HOP_HOME"
```

3. Resulting plugin folder:

- `$HOP_HOME/plugins/transforms/graalpy`

### Option B: Scripted build + sync into Hop home

```bash
./scripts/dev-sync-hop-plugin.sh "$HOP_HOME"
```

Or, if `HOP_HOME` is exported:

```bash
./scripts/dev-sync-hop-plugin.sh
```

## Shell scripts

### `scripts/dev-sync-hop-plugin.sh`

Builds the plugin and assembly, removes the existing target plugin folder and unpacks the ZIP into `HOP_HOME`.

Behavior:

- runs `mvn -q -DskipTests package`
- expects `./assemblies/assemblies-transform-graalpy/target/hop-transform-graalpy-0.1.0-SNAPSHOT.zip`
- removes `$HOP_HOME/plugins/transforms/graalpy` before install

## Tests

Current automated coverage includes:

- metadata XML roundtrip and field merge behavior
- runtime mode semantics for `RETURN_ONE` and `EMIT_MANY`
- strict output-schema enforcement
- type bridge roundtrips for string, integer, number, big number, boolean, `None` and date values
- security restrictions for Java access, file I/O, subprocesses and environment variables
- plugin discovery from an external Hop plugin folder

Run tests:

```bash
mvn test
```

## Python contract

The script must define:

```python
def process(row, ctx):
    return row
```

Supported runtime modes:

- `RETURN_ONE`: return a `dict` or `None`
- `EMIT_MANY`: call `ctx.emit({...})` zero to many times and return `None`

Important:

- Every field that Python returns in `RETURN_ONE` or emits via `ctx.emit(...)` in `EMIT_MANY` must be declared in the `Output Fields` table in the dialog.
- If Python returns or emits a field that is not declared there, the transform fails with an error.
- Existing input fields that should simply pass through unchanged do not need to be declared.
- Existing input fields that Python should overwrite must be declared with `replaceExisting = Yes`.
- New appended fields must be declared with `replaceExisting = No`.

### Example: `RETURN_ONE`

Typical 1:1 row transformation with one replaced field and one appended field:

```python
def process(row, ctx):
    return {
        "name": row["name"].upper(),
        "greeting": f"Hello {row['name']}"
    }
```

Example output field configuration in Hop:

- `name` as `String`, `replaceExisting = Yes`
- `greeting` as `String`, `replaceExisting = No`

Returning `None` filters the current row:

```python
def process(row, ctx):
    if row.get("active") is not True:
        return None
    return row
```

### Example: `EMIT_MANY`

Typical 1:n expansion where one input row emits multiple output rows:

```python
def process(row, ctx):
    count = row.get("count", 0)
    for i in range(count):
        ctx.emit({
            "source_id": row["id"],
            "index": i
        })
    return None
```

Example output field configuration in Hop:

- `source_id` as `Integer` or `String`, depending on the input field, `replaceExisting = No`
- `index` as `Integer`, `replaceExisting = No`

Emitting nothing is valid and produces zero rows for that input row:

```python
def process(row, ctx):
    if row.get("count", 0) <= 0:
        return None
    ctx.emit({"value": row["count"]})
    return None
```

Available helpers:

- `ctx.emit(mapping)`
- `ctx.skip()`
- `ctx.abort(message)`
- `ctx.log.info(...)`, `ctx.log.warn(...)`, `ctx.log.error(...)`
- `ctx.fields`

## V1 limits

- inline scripts only
- static output schema only
- no `yield` or generator contract
- no direct Java access from Python
- no plugin UI support for `sys.path`, virtualenvs or external Python package configuration
