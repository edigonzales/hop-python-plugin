package ch.so.agi.hop.python.transform.graalpy;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.hop.core.row.IRowMeta;

/** Bounded preview using exactly the production runtime. No SWT or upstream pipeline execution. */
public final class PythonPreviewRunner {
  public record Rejected(Object[] input, PythonRuntimeSession.Rejection error) {}

  public record Preview(IRowMeta metadata, List<Object[]> rows, List<Rejected> rejected) {}

  private final PythonRuntimeSession session;

  public PythonPreviewRunner(
      GraalPyTransformMeta configuration,
      Function<String, String> resolve,
      String name,
      BiConsumer<String, String> logger) {
    GraalPyTransformMeta meta = configuration.clone();
    meta.setExecutionTimeoutSeconds(cap(meta.getExecutionTimeoutSeconds(), 30));
    meta.setMaxEmittedRows(cap(meta.getMaxEmittedRows(), 1000));
    meta.setMaxLogBytes(cap(meta.getMaxLogBytes(), 1048576));
    session = new PythonRuntimeSession(meta, resolve, name, 0, logger);
  }

  static long cap(long configured, long maximum) {
    return configured == 0 ? maximum : Math.min(configured, maximum);
  }

  public void cancel() {
    session.cancel("Python preview stopped");
  }

  public Preview run(IRowMeta metadata, List<Object[]> input) throws Exception {
    ScheduledExecutorService deadline =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "graalpy-preview-deadline");
              t.setDaemon(true);
              return t;
            });
    deadline.schedule(
        () -> session.cancel("Python preview exceeded 30 seconds"), 30, TimeUnit.SECONDS);
    try (session) {
      if (input.size() > 100)
        throw new IllegalArgumentException("Preview accepts at most 100 input rows");
      session.open();
      session.prepare(metadata);
      List<Object[]> rows = new ArrayList<>();
      List<Rejected> rejected = new ArrayList<>();
      for (int i = 0; i < input.size(); i++) {
        PythonRuntimeSession.Result result = session.process(input.get(i), i + 1);
        if (rows.size() + result.rows().size() > 1000)
          throw new IllegalStateException("Preview exceeds 1000 output rows");
        rows.addAll(result.rows());
        if (result.rejection() != null)
          rejected.add(new Rejected(input.get(i).clone(), result.rejection()));
      }
      return new Preview(session.outputMeta(), rows, rejected);
    } finally {
      deadline.shutdownNow();
    }
  }
}
