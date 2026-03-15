package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.IVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.swt.widgets.Shell;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;

class GraalPyPluginBuildTest {
  @TempDir Path tempDir;

  @AfterEach
  void resetHop() {
    HopEnvironment.reset();
  }

  @Test
  void jandexIndexIsGeneratedForPluginDiscovery() {
    assertNotNull(getClass().getClassLoader().getResource("META-INF/jandex.idx"));
  }

  @Test
  void pluginLoadsFromExternalPluginFolder() throws Exception {
    Path pluginBaseDir = tempDir.resolve("plugins");
    Path pluginDir = pluginBaseDir.resolve("transforms").resolve("graalpy");
    Files.createDirectories(pluginDir);
    createPluginJar(pluginDir.resolve("hop-transform-graalpy-test.jar"));

    String previous = System.getProperty(Const.HOP_PLUGIN_BASE_FOLDERS);
    try {
      System.setProperty(Const.HOP_PLUGIN_BASE_FOLDERS, pluginBaseDir.toString());
      HopEnvironment.reset();
      HopEnvironment.init();

      URL pluginFolderUrl = pluginBaseDir.toUri().toURL();
      List<IPlugin> folderPlugins = PluginRegistry.getInstance().findPluginsByFolder(pluginFolderUrl);

      assertTrue(
          folderPlugins.stream().flatMap(plugin -> Stream.of(plugin.getIds())).anyMatch("GraalPyTransform"::equals));
    } finally {
      HopEnvironment.reset();
      if (previous == null) {
        System.clearProperty(Const.HOP_PLUGIN_BASE_FOLDERS);
      } else {
        System.setProperty(Const.HOP_PLUGIN_BASE_FOLDERS, previous);
      }
    }
  }

  @Test
  void dialogBridgeMatchesHopGuiReflectionContract() throws Exception {
    Constructor<GraalPyTransformDialog> constructor =
        GraalPyTransformDialog.class.getConstructor(
            Shell.class, IVariables.class, Object.class, PipelineMeta.class, String.class);
    assertNotNull(constructor);

    Method method =
        GraalPyTransformMeta.class.getDeclaredMethod(
            "getDialog",
            Shell.class,
            IVariables.class,
            ITransformMeta.class,
            PipelineMeta.class,
            String.class);
    assertNotNull(method);
  }

  @Test
  void packageScopedResourcesAreOnTheClasspath() {
    assertNotNull(
        getClass()
            .getClassLoader()
            .getResource("ch/so/agi/hop/python/transform/graalpy/icons/graalpy.svg"));
    assertNotNull(
        getClass()
            .getClassLoader()
            .getResource("ch/so/agi/hop/python/transform/graalpy/messages/messages_en_US.properties"));
  }

  private void createPluginJar(Path jarFile) throws IOException {
    Path classesDir = Path.of("target", "classes");
    try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarFile));
        Stream<Path> files = Files.walk(classesDir).sorted(Comparator.naturalOrder())) {
      for (Path path : (Iterable<Path>) files::iterator) {
        if (Files.isDirectory(path)) {
          continue;
        }
        String entryName = classesDir.relativize(path).toString().replace('\\', '/');
        jarOutputStream.putNextEntry(new JarEntry(entryName));
        try (InputStream inputStream = Files.newInputStream(path)) {
          copy(inputStream, jarOutputStream);
        }
        jarOutputStream.closeEntry();
      }
    }
  }

  private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    while ((read = inputStream.read(buffer)) >= 0) {
      outputStream.write(buffer, 0, read);
    }
  }
}
