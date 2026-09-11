package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.apache.hop.core.HopEnvironment;
import org.junit.jupiter.api.*;

class PythonEnvironmentIntegrationTest {
  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  @AfterAll
  static void reset() {
    HopEnvironment.reset();
  }

  @Test
  void rejectsAnActualCpythonEnvironment() throws Exception {
    String path = System.getProperty("graalpy.test.cpython");
    Assumptions.assumeTrue(path != null, "CI provisions a CPython negative fixture");
    GraalPyExternalEnvironment env = GraalPyExternalEnvironment.resolve(true, path);
    Exception failure = assertThrows(Exception.class, env::verify);
    assertTrue(failure.getMessage().contains("Expected GraalPy"));
    assertTrue(failure.getMessage().contains("cpython"));
  }

  @Test
  void purePackageWorksAcrossConcurrentCopiesAndRepeatedSessions() throws Exception {
    String path = System.getProperty("graalpy.test.venv");
    Assumptions.assumeTrue(
        path != null,
        "Provision with scripts/setup-test-venv.py; CI always sets graalpy.test.venv");
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      for (int run = 0; run < 2; run++) {
        List<Future<Long>> results = new ArrayList<>();
        for (int copy = 0; copy < 2; copy++) {
          int nr = copy;
          results.add(
              workers.submit(
                  () -> {
                    GraalPyTransformMeta meta =
                        PythonRuntimeSessionTest.meta(
                            """
                from packaging.version import Version
                def setup(ctx): ctx.state['n'] = 0
                def process(row, ctx):
                    assert Version('2.0') > Version('1.0')
                    ctx.state['n'] += 1
                    return {'value': ctx.state['n'] + ctx.copy_nr}
                """);
                    meta.setExternalEnvironmentEnabled(true);
                    meta.setGraalPyVenvPath(path);
                    meta.setNativeAccessEnabled(false);
                    meta.setOutputFields(
                        List.of(PythonRuntimeSessionTest.field("value", "Integer", true)));
                    try (PythonRuntimeSession session =
                        new PythonRuntimeSession(meta, s -> s, "package-" + nr, nr, (l, m) -> {})) {
                      session.open();
                      session.prepare(PythonRuntimeSessionTest.input());
                      return (Long) session.process(new Object[] {0L}, 1).rows().get(0)[0];
                    }
                  }));
        }
        assertEquals(1L, results.get(0).get(60, TimeUnit.SECONDS));
        assertEquals(2L, results.get(1).get(60, TimeUnit.SECONDS));
      }
    } finally {
      workers.shutdownNow();
    }
  }
}
