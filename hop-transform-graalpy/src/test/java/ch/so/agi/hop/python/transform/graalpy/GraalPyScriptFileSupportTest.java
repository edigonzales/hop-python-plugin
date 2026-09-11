package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraalPyScriptFileSupportTest {
  @TempDir Path tempDir;

  @Test
  void loadReadsUtf8Script() throws Exception {
    Path scriptFile = tempDir.resolve("example.py");
    Files.writeString(scriptFile, "# Grüezi\nprint('äöü')\n", StandardCharsets.UTF_8);

    String script = GraalPyScriptFileSupport.load(scriptFile);

    assertEquals("# Grüezi\nprint('äöü')\n", script);
  }

  @Test
  void saveWritesUtf8Script() throws Exception {
    Path scriptFile = tempDir.resolve("saved.py");

    GraalPyScriptFileSupport.save(scriptFile, "print('äöü')\n");

    assertEquals("print('äöü')\n", Files.readString(scriptFile, StandardCharsets.UTF_8));
  }
}
