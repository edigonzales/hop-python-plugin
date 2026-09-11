package ch.so.agi.hop.python.transform.graalpy;

import java.util.*;
import java.util.concurrent.*;
import org.apache.hop.core.HopEnvironment;
import org.graalvm.polyglot.Context;

/** Launched only in a disposable JVM, with an outer process deadline. */
public final class RuntimeCancellationProbe {
  public static void main(String[] args) throws Exception {
    String phase = args[0];
    HopEnvironment.init();
    try (Context warm =
        Context.newBuilder("python").option("engine.WarnInterpreterOnly", "false").build()) {
      // Warm the imports used by every session, not only the Python language itself.
      new HopPythonTypeBridge(warm);
    }
    String script =
        switch (phase) {
          case "load" -> "while True: pass\ndef process(row, ctx): return {}";
          case "setup" -> "def setup(ctx):\n    while True: pass\ndef process(row, ctx): return {}";
          case "close" -> "def process(row, ctx): return {}\ndef close(ctx):\n    while True: pass";
          default -> "def process(row, ctx):\n    while True: pass";
        };
    GraalPyTransformMeta meta = PythonRuntimeSessionTest.meta(script);
    meta.setExecutionTimeoutSeconds(phase.equals("stop") ? 0 : 10);
    PythonRuntimeSession session = PythonRuntimeSessionTest.session(meta, new ArrayList<>());
    ScheduledExecutorService stopper = Executors.newSingleThreadScheduledExecutor();
    boolean aborted = false;
    try (session) {
      session.open();
      session.prepare(PythonRuntimeSessionTest.input());
      if (phase.equals("stop"))
        stopper.schedule(
            () -> {
              session.cancel();
            },
            500,
            TimeUnit.MILLISECONDS);
      if (!phase.equals("close")) session.process(new Object[] {1L}, 1);
    } catch (Exception e) {
      String expected = phase.equals("stop") ? "cancelled" : "timeout in " + phase;
      if (!e.getMessage().contains(expected)) throw e;
      aborted = true;
    } finally {
      stopper.shutdownNow();
      HopEnvironment.reset();
    }
    if (!aborted) throw new AssertionError("Execution was not cancelled");
    System.out.println("CANCELLATION_OK " + phase);
  }
}
