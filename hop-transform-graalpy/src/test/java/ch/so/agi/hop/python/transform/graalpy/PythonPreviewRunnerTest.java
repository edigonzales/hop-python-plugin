package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.junit.jupiter.api.*;

class PythonPreviewRunnerTest {
  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  @AfterAll
  static void reset() {
    HopEnvironment.reset();
  }

  @Test
  void previewShowsRejectedRowsAndDoesNotMutateConfiguration() throws Exception {
    GraalPyTransformMeta meta =
        PythonRuntimeSessionTest.meta(
            "def process(row, ctx):\n"
                + "    if row['value'] < 0: ctx.reject('NEGATIVE', 'Negative value')\n"
                + "    return {}");
    meta.setExecutionTimeoutSeconds(0);
    PythonPreviewRunner runner = new PythonPreviewRunner(meta, s -> s, "preview", (l, m) -> {});
    PythonPreviewRunner.Preview preview =
        runner.run(
            PythonRuntimeSessionTest.input(), List.of(new Object[] {1L}, new Object[] {-1L}));
    assertEquals(1, preview.rows().size());
    assertEquals(1, preview.rejected().size());
    assertEquals("NEGATIVE", preview.rejected().get(0).error().code());
    assertEquals(0, meta.getExecutionTimeoutSeconds());
  }

  @Test
  void capsTotalOutputsAndPreservesStricterLimits() throws Exception {
    assertEquals(2, PythonPreviewRunner.cap(2, 30));
    assertEquals(30, PythonPreviewRunner.cap(0, 30));
    GraalPyTransformMeta meta =
        PythonRuntimeSessionTest.meta(
            "def process(row, ctx):\n    for i in range(600): ctx.emit({})");
    meta.setExecutionMode("EMIT_MANY");
    PythonPreviewRunner runner = new PythonPreviewRunner(meta, s -> s, "preview", (l, m) -> {});
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                runner.run(
                    PythonRuntimeSessionTest.input(),
                    List.of(new Object[] {1L}, new Object[] {2L})));
    assertTrue(e.getMessage().contains("1000"));
  }
}
