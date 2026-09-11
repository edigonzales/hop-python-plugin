package ch.so.agi.hop.python.transform.graalpy;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.i18n.BaseMessages;

record GraalPyExternalEnvironment(boolean enabled, Path venvPath, Path executablePath) {
  private static final Class<?> PKG = GraalPyExternalEnvironment.class;
  private static final List<String> UNIX_EXECUTABLES =
      List.of("bin/graalpy", "bin/python3", "bin/python");
  private static final List<String> WINDOWS_EXECUTABLES =
      List.of("Scripts/graalpy.exe", "Scripts/python.exe", "Scripts/python3.exe");

  static GraalPyExternalEnvironment disabled() {
    return new GraalPyExternalEnvironment(false, null, null);
  }

  static GraalPyExternalEnvironment resolve(boolean enabled, String configuredVenvPath)
      throws HopTransformException {
    if (!enabled) {
      return disabled();
    }
    if (StringUtils.isBlank(configuredVenvPath)) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.VenvPathMissing"));
    }

    Path venvPath;
    try {
      venvPath = Paths.get(configuredVenvPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      throw new HopTransformException(
          BaseMessages.getString(
              PKG, "GraalPyTransform.Exception.VenvPathInvalid", configuredVenvPath),
          e);
    }

    if (!Files.isDirectory(venvPath)) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.VenvDirectoryMissing", venvPath));
    }
    if (!Files.isRegularFile(venvPath.resolve("pyvenv.cfg"))) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.PyVenvCfgMissing", venvPath));
    }

    return new GraalPyExternalEnvironment(
        true, venvPath, resolveExecutable(venvPath, System.getProperty("os.name")));
  }

  static final String EXPECTED_VERSION = "25.3.4.1";

  void verify() throws Exception {
    if (!enabled) return;
    // Fixed program, isolated startup and no shell; do not run venv site customizations.
    String program =
        """
        import sys, pathlib
        print(sys.implementation.name)
        print('.'.join(map(str, getattr(sys, 'graalpy_version_info', ())[:3])))
        release = pathlib.Path(sys.base_prefix) / 'release'
        values = dict(line.split('=', 1) for line in release.read_text().splitlines() if '=' in line) if release.is_file() else {}
        print(values.get('GRAALVM_VERSION', '').strip(chr(34)))
        """;
    Path output = Files.createTempFile("hop-graalpy-probe-", ".txt");
    Process process = null;
    try {
      process =
          new ProcessBuilder(executablePath.toString(), "-I", "-S", "-c", program)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        throw new HopTransformException(
            "GraalPy environment diagnostic timed out after 10 seconds");
      if (Files.size(output) > 65536)
        throw new HopTransformException("Unexpected environment diagnostic output");
      String result = Files.readString(output).trim();
      String[] lines = result.split("\\R");
      if (process.exitValue() != 0
          || lines.length != 3
          || !"graalpy".equals(lines[0])
          || !"25.3.4".equals(lines[1])
          || !EXPECTED_VERSION.equals(lines[2]))
        throw new HopTransformException(
            "Expected GraalPy " + EXPECTED_VERSION + "; environment reported: " + result);
    } finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
      }
      Files.deleteIfExists(output);
    }
  }

  static Path resolveExecutable(Path venvPath, String osName) throws HopTransformException {
    for (String candidate : executableCandidates(osName)) {
      Path executable = venvPath.resolve(candidate);
      if (Files.isRegularFile(executable)) {
        return executable;
      }
    }
    throw new HopTransformException(
        BaseMessages.getString(PKG, "GraalPyTransform.Exception.VenvExecutableMissing", venvPath));
  }

  static List<String> executableCandidates(String osName) {
    return isWindows(osName) ? WINDOWS_EXECUTABLES : UNIX_EXECUTABLES;
  }

  private static boolean isWindows(String osName) {
    return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
  }
}
