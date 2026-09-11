package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.pipeline.PipelineMeta;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in real SWT interaction; CI runs it with xvfb and macOS uses the first-thread JVM flag. */
class PythonDialogSmokeTest {
  @Test
  void opensPreviewRunsStopsAndCancelsWithoutChangingMetadata() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("graalpy.test.ui"));
    HopEnvironment.init();
    Display display = Display.getDefault();
    Shell parent = new Shell(display);
    try {
      for (boolean stop : List.of(false, true)) {
        GraalPyTransformMeta meta =
            PythonRuntimeSessionTest.meta(
                stop
                    ? "def process(row, ctx):\n    while True: pass"
                    : "def process(row, ctx):\n    return {}");
        String original = meta.getXml();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
        int[] state = {0};
        Runnable driver =
            new Runnable() {
              public void run() {
                try {
                  if (System.nanoTime() > deadline)
                    throw new AssertionError("SWT preview timed out at state " + state[0]);
                  if (state[0] == 0) {
                    button(display, "Test…").notifyListeners(SWT.Selection, new Event());
                    state[0]++;
                  } else if (state[0] == 1) {
                    button(display, "Run").notifyListeners(SWT.Selection, new Event());
                    state[0]++;
                  } else if (state[0] == 2 && stop) {
                    boolean loaded =
                        controls(display).stream()
                            .anyMatch(c -> c instanceof Text t && t.getText().contains("SHA-256="));
                    if (loaded) {
                      button(display, "Stop").notifyListeners(SWT.Selection, new Event());
                      state[0]++;
                    }
                  } else if ((state[0] == 2 && !stop || state[0] == 3)
                      && button(display, "Run").isEnabled()) {
                    if (stop)
                      assertTrue(
                          controls(display).stream()
                              .anyMatch(
                                  c -> c instanceof Text t && t.getText().contains("stopped")));
                    button(display, "Close").notifyListeners(SWT.Selection, new Event());
                    button(display, "Cancel").notifyListeners(SWT.Selection, new Event());
                    return;
                  }
                  display.timerExec(100, this);
                } catch (Throwable e) {
                  failure.set(e);
                  for (Shell shell : display.getShells()) if (shell != parent) shell.dispose();
                }
              }
            };
        display.timerExec(200, driver);
        assertNull(
            new GraalPyTransformDialog(parent, new Variables(), meta, new PipelineMeta(), "Smoke")
                .open());
        if (failure.get() != null) throw new AssertionError("UI interaction failed", failure.get());
        assertEquals(original, meta.getXml());
      }
    } finally {
      parent.dispose();
      HopEnvironment.reset();
    }
  }

  private static Button button(Display display, String text) {
    return (Button)
        controls(display).stream()
            .filter(c -> c instanceof Button b && b.getText().equals(text))
            .findFirst()
            .orElseThrow();
  }

  private static List<Control> controls(Display display) {
    List<Control> result = new ArrayList<>();
    for (Shell shell : display.getShells()) visit(shell, result);
    return result;
  }

  private static void visit(Control control, List<Control> result) {
    result.add(control);
    if (control instanceof Composite composite)
      for (Control child : composite.getChildren()) visit(child, result);
  }
}
