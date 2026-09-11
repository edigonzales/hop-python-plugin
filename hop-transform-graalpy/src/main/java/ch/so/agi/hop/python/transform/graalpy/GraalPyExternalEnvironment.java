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
  private static final List<String> UNIX_EXECUTABLES = List.of("bin/graalpy", "bin/python3", "bin/python");
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
      venvPath = Paths.get(configuredVenvPath).normalize();
    } catch (InvalidPathException e) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.VenvPathInvalid", configuredVenvPath),
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

    return new GraalPyExternalEnvironment(true, venvPath, resolveExecutable(venvPath, System.getProperty("os.name")));
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
