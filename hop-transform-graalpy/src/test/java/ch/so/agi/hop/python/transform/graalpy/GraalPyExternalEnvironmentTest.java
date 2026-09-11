package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.exception.HopTransformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraalPyExternalEnvironmentTest {
  @TempDir Path tempDir;

  @Test
  void disabledEnvironmentReturnsDisabledMarker() throws Exception {
    GraalPyExternalEnvironment environment = GraalPyExternalEnvironment.resolve(false, "");

    assertFalse(environment.enabled());
    assertEquals(null, environment.venvPath());
    assertEquals(null, environment.executablePath());
  }

  @Test
  void resolveRejectsMissingPyVenvCfg() {
    HopTransformException exception =
        assertThrows(HopTransformException.class, () -> GraalPyExternalEnvironment.resolve(true, tempDir.toString()));

    assertTrue(exception.getMessage().contains("pyvenv.cfg"));
  }

  @Test
  void resolveRejectsMissingExecutable() throws Exception {
    Files.createFile(tempDir.resolve("pyvenv.cfg"));

    HopTransformException exception =
        assertThrows(HopTransformException.class, () -> GraalPyExternalEnvironment.resolve(true, tempDir.toString()));

    assertTrue(exception.getMessage().contains("launcher"));
  }

  @Test
  void resolveExecutableFindsUnixLaunchersInPriorityOrder() throws Exception {
    Files.createFile(tempDir.resolve("pyvenv.cfg"));
    Files.createDirectories(tempDir.resolve("bin"));
    Files.createFile(tempDir.resolve("bin").resolve("python3"));

    Path executable = GraalPyExternalEnvironment.resolveExecutable(tempDir, "Mac OS X");

    assertEquals(tempDir.resolve("bin").resolve("python3"), executable);
  }

  @Test
  void resolveExecutableFindsWindowsLaunchersInPriorityOrder() throws Exception {
    Files.createDirectories(tempDir.resolve("Scripts"));
    Files.createFile(tempDir.resolve("Scripts").resolve("python.exe"));
    Files.createFile(tempDir.resolve("Scripts").resolve("graalpy.exe"));

    Path executable = GraalPyExternalEnvironment.resolveExecutable(tempDir, "Windows 11");

    assertEquals(tempDir.resolve("Scripts").resolve("python.exe"), executable);
  }
}
