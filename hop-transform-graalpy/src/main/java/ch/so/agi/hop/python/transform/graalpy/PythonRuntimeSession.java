package ch.so.agi.hop.python.transform.graalpy;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.row.*;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;

/** Single-owner execution; cancellation is the only cross-thread context operation. */
public final class PythonRuntimeSession implements AutoCloseable {
  public record Rejection(String code, String message, String field) {}

  public record Result(List<Object[]> rows, Rejection rejection) {}

  private final GraalPyTransformMeta meta;
  private final Function<String, String> resolve;
  private final String name;
  private final int copy;
  private final PythonSessionLog log;
  private final ScheduledThreadPoolExecutor watchdog;
  private final Object lifecycle = new Object();
  private volatile Context context;
  private java.io.OutputStream guestOut, guestErr;
  private volatile boolean cancelled;
  private boolean closed, setupCalled, scriptLoaded, failed;
  private long generation;
  private String cancellationReason = "Python execution cancelled";
  private String scriptName;
  private Value process, setup, closeHook, pythonContext;
  private HopPythonTypeBridge bridge;
  private PyContextFacade facade;
  private OutputRowAssembler assembler;
  private IRowMeta inputMeta, selectedMeta, outputMeta;
  private int[] selectedIndices;
  private final Map<String, String> parameters = new LinkedHashMap<>();

  public PythonRuntimeSession(
      GraalPyTransformMeta meta,
      Function<String, String> resolve,
      String name,
      int copy,
      BiConsumer<String, String> logger) {
    this.meta = meta.clone();
    this.resolve = resolve;
    this.name = name;
    this.copy = copy;
    this.scriptName = name + ".py";
    log = new PythonSessionLog(meta.getMaxLogBytes(), logger);
    watchdog =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "graalpy-control-" + name + "-" + copy);
              thread.setDaemon(true);
              return thread;
            });
    watchdog.setRemoveOnCancelPolicy(true);
  }

  public void open() throws HopTransformException {
    phase(
        "load",
        0,
        () -> {
          meta.validate(null);
          for (GraalPyParameter parameter : meta.getParameters())
            parameters.put(
                parameter.getName(), resolve.apply(Objects.toString(parameter.getValue(), "")));
          GraalPyExternalEnvironment env =
              GraalPyExternalEnvironment.resolve(
                  meta.isExternalEnvironmentEnabled(), resolve.apply(meta.getGraalPyVenvPath()));
          if (env.enabled()) env.verify();
          guestOut = log.stream("INFO");
          guestErr = log.stream("ERROR");
          Context.Builder builder =
              Context.newBuilder("python")
                  .allowHostAccess(HostAccess.EXPLICIT)
                  .allowHostClassLookup(type -> false)
                  .allowEnvironmentAccess(EnvironmentAccess.NONE)
                  .allowCreateProcess(false)
                  .allowCreateThread(false)
                  .allowPolyglotAccess(PolyglotAccess.NONE)
                  .allowNativeAccess(env.enabled() && meta.isNativeAccessEnabled())
                  .allowIO(env.enabled() ? IOAccess.ALL : IOAccess.NONE)
                  .out(guestOut)
                  .err(guestErr)
                  .option("engine.WarnInterpreterOnly", "false");
          if (env.enabled()) builder.option("python.Executable", env.executablePath().toString());
          Context created = builder.build();
          synchronized (lifecycle) {
            context = created;
            if (cancelled) {
              created.close(true);
              throw new IllegalStateException(cancellationReason);
            }
          }
          if (env.enabled()) {
            context.eval("python", "import site");
            Path actual =
                Path.of(context.eval("python", "__import__('sys').prefix").asString()).toRealPath();
            if (!actual.equals(env.venvPath().toRealPath()))
              throw new IllegalStateException(
                  "Embedded venv mismatch: expected " + env.venvPath() + ", found " + actual);
          }
          log.write(
              "INFO",
              "GraalPy distribution "
                  + GraalPyExternalEnvironment.EXPECTED_VERSION
                  + "; language="
                  + context
                      .eval(
                          "python",
                          "'.'.join(map(str, __import__('sys').graalpy_version_info[:3]))")
                      .asString()
                  + "; runtime="
                  + context.getEngine().getImplementationName()
                  + "; venv="
                  + (env.enabled() ? env.venvPath() : "disabled"));
          bridge = new HopPythonTypeBridge(context);
          String script = meta.getScriptText();
          if ("FILE".equals(meta.getScriptSource())) {
            Path path = Path.of(resolve.apply(meta.getScriptPath()));
            if (!path.isAbsolute()) {
              String home = resolve.apply("${PROJECT_HOME}");
              if (home == null || home.isBlank() || home.contains("${"))
                throw new IllegalArgumentException("Relative script paths require PROJECT_HOME");
              path = Path.of(home).resolve(path);
            }
            path = path.toAbsolutePath().normalize();
            scriptName = path.toString();
            script = Files.readString(path, StandardCharsets.UTF_8);
          }
          String hash =
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(script.getBytes(StandardCharsets.UTF_8)));
          log.write("INFO", "Python script " + scriptName + "; SHA-256=" + hash);
          context.eval(Source.newBuilder("python", script, scriptName).buildLiteral());
          scriptLoaded = true;
          process = function("process", true);
          setup = function("setup", false);
          closeHook = function("close", false);
          return null;
        });
  }

  private Value function(String functionName, boolean required) {
    Value value = context.getBindings("python").getMember(functionName);
    if (value == null && !required) return null;
    if (value == null || !value.canExecute())
      throw new IllegalArgumentException(functionName + " must be callable");
    return value;
  }

  public void prepare(IRowMeta incoming) throws HopTransformException {
    if (setupCalled) return;
    phase(
        "setup",
        0,
        () -> {
          inputMeta = incoming == null ? new RowMeta() : incoming.clone();
          meta.validate(inputMeta);
          outputMeta = inputMeta.clone();
          meta.getFields(outputMeta, name, null, null, null, null);
          assembler = new OutputRowAssembler(inputMeta, outputMeta, meta.getOutputFields());
          selectedMeta = new RowMeta();
          List<Integer> indices = new ArrayList<>();
          Set<String> selected = new LinkedHashSet<>();
          meta.getSelectedInputs().forEach(f -> selected.add(f.getName()));
          for (int i = 0; i < inputMeta.size(); i++) {
            if ("ALL".equals(meta.getInputMode())
                || selected.contains(inputMeta.getValueMeta(i).getName())) {
              indices.add(i);
              selectedMeta.addValueMeta(inputMeta.getValueMeta(i));
            }
          }
          selectedIndices = indices.stream().mapToInt(Integer::intValue).toArray();
          bridge.validateInputTypes(selectedMeta);
          facade =
              new PyContextFacade(
                  meta.getExecutionModeEnum(),
                  assembler,
                  bridge,
                  new PyLoggerFacade(log),
                  assembler.buildFieldMetadata(selectedMeta));
          facade.setMaxEmittedRows(meta.getMaxEmittedRows());
          Value dictionary = context.eval("python", "dict").execute();
          parameters.forEach(dictionary::putHashEntry);
          pythonContext =
              context
                  .eval("python", CONTEXT_FACTORY)
                  .execute(
                      facade,
                      dictionary,
                      metadata(selectedMeta),
                      metadata(outputMeta),
                      name,
                      copy,
                      bridge.geometryFactory());
          setupCalled = true;
          invokeHook(setup, "setup");
          return null;
        });
  }

  private Value metadata(IRowMeta metadata) {
    Value list = context.eval("python", "list").execute();
    for (Map<String, Object> field : assembler.buildFieldMetadata(metadata)) {
      Value dict = context.eval("python", "dict").execute();
      field.forEach(dict::putHashEntry);
      list.invokeMember("append", dict);
    }
    return list;
  }

  public IRowMeta outputMeta() {
    return outputMeta;
  }

  public Result process(Object[] input, long rowNumber) throws HopTransformException {
    if (!setupCalled) throw new IllegalStateException("prepare must run before process");
    return phase(
        "process",
        rowNumber,
        () -> {
          facade.beginRow(input);
          try {
            Object[] projected = new Object[selectedIndices.length];
            for (int i = 0; i < selectedIndices.length; i++)
              projected[i] = input[selectedIndices[i]];
            Value result =
                process.execute(bridge.createPythonRow(selectedMeta, projected), pythonContext);
            facade.checkSignal();
            if (meta.getExecutionModeEnum() == GraalPyExecutionMode.RETURN_ONE) {
              if (result == null || result.isNull()) return new Result(List.of(), null);
              return new Result(
                  Collections.singletonList(
                      assembler.createOutputRow(input, bridge.snapshotMapping(result, assembler))),
                  null);
            }
            if (result != null && !result.isNull())
              throw new IllegalArgumentException("EMIT_MANY process must return None");
            return new Result(facade.drainEmittedRows(), null);
          } catch (Exception e) {
            Throwable cause =
                e instanceof PolyglotException polyglot && polyglot.isHostException()
                    ? polyglot.asHostException()
                    : e;
            if (cause instanceof SkipCurrentRowException) return new Result(List.of(), null);
            if (cause instanceof RejectCurrentRowException rejection)
              return new Result(
                  List.of(),
                  new Rejection(rejection.code, rejection.getMessage(), rejection.field));
            throw e;
          } finally {
            facade.endRow();
          }
        });
  }

  public void cancel() {
    cancel("Python execution cancelled");
  }

  public void cancel(String reason) {
    synchronized (lifecycle) {
      if (cancelled || closed) return;
      cancelled = true;
      cancellationReason = reason;
      generation++;
      watchdog.execute(
          () -> {
            Context current = context;
            if (current != null) current.close(true);
          });
    }
  }

  private void invokeHook(Value hook, String phaseName) {
    if (hook != null) {
      Value result = hook.execute(pythonContext);
      if (facade != null) facade.checkSignal();
      if (result != null && !result.isNull())
        throw new IllegalArgumentException(phaseName + " must return None");
    }
  }

  private <T> T phase(String phaseName, long row, Checked<T> action) throws HopTransformException {
    long token;
    ScheduledFuture<?> timeout = null;
    synchronized (lifecycle) {
      if (cancelled || closed) throw new HopTransformException(cancellationReason);
      token = ++generation;
      if (meta.getExecutionTimeoutSeconds() > 0) {
        timeout =
            watchdog.schedule(
                () -> {
                  synchronized (lifecycle) {
                    if (generation != token || closed || cancelled) return;
                    cancelled = true;
                    cancellationReason = "Python timeout in " + phaseName;
                    generation++;
                  }
                  Context current = context;
                  if (current != null) current.close(true);
                },
                meta.getExecutionTimeoutSeconds(),
                TimeUnit.SECONDS);
      }
    }
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      T result = action.call();
      synchronized (lifecycle) {
        if (cancelled) throw new IllegalStateException(cancellationReason);
        if (generation == token) generation++;
      }
      return result;
    } catch (Exception e) {
      failed = true;
      StringBuilder detail =
          new StringBuilder(
              cancelled ? cancellationReason : Objects.toString(e.getMessage(), e.toString()));
      if (e instanceof PolyglotException polyglot) {
        for (PolyglotException.StackFrame frame : polyglot.getPolyglotStackTrace())
          if (frame.isGuestFrame()) detail.append("\n").append(frame);
      }
      throw new HopTransformException(
          "Python "
              + phaseName
              + " ["
              + scriptName
              + ", transform="
              + name
              + ", copy="
              + copy
              + (row > 0 ? ", row=" + row : "")
              + "]: "
              + detail,
          e);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
      synchronized (lifecycle) {
        if (generation == token) generation++;
      }
      if (timeout != null) timeout.cancel(false);
      try {
        if (guestOut != null) guestOut.flush();
        if (guestErr != null) guestErr.flush();
      } catch (java.io.IOException e) {
        log.write("ERROR", "Cannot flush Python log: " + e.getMessage());
      }
    }
  }

  @Override
  public void close() throws HopTransformException {
    synchronized (lifecycle) {
      if (closed) return;
    }
    boolean originalFailure = failed;
    try {
      if (!cancelled && context != null && scriptLoaded && setupCalled && closeHook != null) {
        phase(
            "close",
            0,
            () -> {
              invokeHook(closeHook, "close");
              return null;
            });
      }
    } catch (HopTransformException e) {
      if (originalFailure) log.write("ERROR", e.getMessage());
      else throw e;
    } finally {
      synchronized (lifecycle) {
        closed = true;
        generation++;
      }
      try {
        if (context != null) context.close(cancelled);
      } finally {
        watchdog.shutdownNow();
      }
    }
  }

  @FunctionalInterface
  private interface Checked<T> {
    T call() throws Exception;
  }

  private static final String CONTEXT_FACTORY =
      """
      def _hop_context(host, parameters, inputs, outputs, name, copy, geometry):
          from types import MappingProxyType
          class GeometryFactory:
              def from_wkt(self, text, srid=0): return geometry.fromWkt(text, srid)
              def from_wkb(self, data, srid=None): return geometry.fromWkb(data, srid)
          class Context:
              def emit(self, mapping): host.emit(mapping)
              def skip(self): host.skip()
              def abort(self, message): host.abort(message)
              def reject(self, code, message, field=None): host.reject(code, message, field)
          ctx = Context()
          ctx.parameters = MappingProxyType(parameters)
          ctx.state = {}
          ctx.input_fields = tuple(MappingProxyType(f) for f in inputs)
          ctx.fields = ctx.input_fields
          ctx.output_fields = tuple(MappingProxyType(f) for f in outputs)
          ctx.transform_name = name
          ctx.copy_nr = copy
          ctx.log = host.getLog()
          ctx.geometry = GeometryFactory()
          return ctx
      _hop_context
      """;
}
