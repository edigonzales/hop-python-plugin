package ch.so.agi.hop.python.transform.graalpy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class GraalPyScriptFileSupport {
  private GraalPyScriptFileSupport() {}

  static String load(Path path) throws IOException {
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  static void save(Path path, String scriptText) throws IOException {
    Files.writeString(path, scriptText, StandardCharsets.UTF_8);
  }
}
