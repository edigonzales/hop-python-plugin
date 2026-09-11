package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonCancellationTest {
  @TempDir Path temp;

  @Test
  void allPhasesAndExplicitStopTerminateInIsolatedProcesses() throws Exception {
    for (String phase : List.of("load", "setup", "process", "close", "stop")) {
      String executable =
          Path.of(
                  System.getProperty("java.home"),
                  "bin",
                  System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
              .toString();
      Path output = temp.resolve(phase + ".log");
      Process process =
          new ProcessBuilder(
                  executable,
                  "-cp",
                  System.getProperty(
                      "surefire.test.class.path", System.getProperty("java.class.path")),
                  RuntimeCancellationProbe.class.getName(),
                  phase)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      try {
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Cancellation process hung: " + phase);
        assertEquals(
            0,
            process.exitValue(),
            () -> {
              try {
                return Files.readString(output);
              } catch (Exception e) {
                return e.toString();
              }
            });
        assertTrue(Files.readString(output).contains("CANCELLATION_OK " + phase));
      } finally {
        if (process.isAlive()) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
      }
    }
  }
}
