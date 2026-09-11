package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.*;
import org.apache.hop.core.row.value.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PythonRuntimeSessionTest {
  @TempDir Path temp;

  @BeforeAll
  static void initialize() throws Exception {
    HopEnvironment.init();
  }

  @AfterAll
  static void reset() {
    HopEnvironment.reset();
  }

  static GraalPyTransformMeta meta(String script) {
    GraalPyTransformMeta m = new GraalPyTransformMeta();
    m.setScriptText(script);
    return m;
  }

  static GraalPyOutputField field(String name, String type, boolean replace) {
    GraalPyOutputField field = new GraalPyOutputField();
    field.setName(name);
    field.setType(type);
    field.setReplaceExisting(replace);
    return field;
  }

  static RowMeta input() {
    RowMeta m = new RowMeta();
    m.addValueMeta(new ValueMetaInteger("value"));
    return m;
  }

  static PythonRuntimeSession session(GraalPyTransformMeta meta, List<String> logs) {
    return new PythonRuntimeSession(
        meta, text -> text, "test", 2, (level, message) -> logs.add(message));
  }

  @Test
  void pureFilterAndStateAndHooksWithEmptyInput() throws Exception {
    List<String> logs = new ArrayList<>();
    GraalPyTransformMeta meta =
        meta(
            """
        def setup(ctx):
            assert ctx.copy_nr == 2
            assert ctx.transform_name == 'test'
            ctx.state['calls'] = 0
            ctx.log.info('setup')
        def process(row, ctx):
            ctx.state['calls'] += 1
            return {} if row['value'] > 0 else None
        def close(ctx):
            ctx.log.info(str(ctx.state['calls']))
        """);
    try (PythonRuntimeSession session = session(meta, logs)) {
      session.open();
      session.prepare(input());
      session.prepare(input());
      assertEquals(1, session.process(new Object[] {1L}, 1).rows().size());
      assertTrue(session.process(new Object[] {-1L}, 2).rows().isEmpty());
    }
    assertEquals(1, logs.stream().filter("setup"::equals).count());
    assertTrue(logs.contains("2"));
    logs.clear();
    try (PythonRuntimeSession session = session(meta, logs)) {
      session.open();
      session.prepare(input());
    }
    assertTrue(logs.contains("0"));
  }

  @Test
  void emitReplaceDoesNotMutateInputAndRejectAndSkipDiscardOutputs() throws Exception {
    for (String ending :
        List.of("ctx.skip()", "ctx.reject('BAD', 'bad row', field='value')", "return None")) {
      GraalPyTransformMeta meta =
          meta(
              "def process(row, ctx):\n    ctx.emit({'value': 1})\n    ctx.emit({'value': 2})\n    "
                  + ending);
      meta.setExecutionMode("EMIT_MANY");
      meta.setOutputFields(List.of(field("value", "Integer", true)));
      Object[] input = {9L, "spare", null};
      try (PythonRuntimeSession session = session(meta, new ArrayList<>())) {
        session.open();
        session.prepare(input());
        PythonRuntimeSession.Result result = session.process(input, 1);
        assertEquals(9L, input[0]);
        if (ending.startsWith("return")) {
          assertEquals(2, result.rows().size());
          assertEquals(1L, result.rows().get(0)[0]);
          assertEquals(2L, result.rows().get(1)[0]);
          assertNotSame(result.rows().get(0), result.rows().get(1));
        } else assertTrue(result.rows().isEmpty());
        if (ending.contains("reject")) assertEquals("BAD", result.rejection().code());
      }
    }
  }

  @Test
  void selectedInputsLeaveUnsupportedValuesUntouched() throws Exception {
    GraalPyTransformMeta meta =
        meta("def process(row, ctx):\n    assert list(row) == ['value']\n    return {}");
    meta.setInputMode("SELECTED");
    meta.setSelectedInputs(List.of(new GraalPyInputField("value")));
    RowMeta metadata = input();
    metadata.addValueMeta(new ValueMetaSerializable("opaque"));
    Object opaque = new Object();
    try (PythonRuntimeSession session = session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(metadata);
      assertSame(opaque, session.process(new Object[] {1L, opaque}, 1).rows().get(0)[1]);
    }
  }

  @Test
  void fileIsLoadedOnceAndParametersAreValues() throws Exception {
    Path file = temp.resolve("process.py");
    Files.writeString(
        file, "def process(row, ctx):\n    return {'value': int(ctx.parameters['number'])}");
    GraalPyTransformMeta meta = meta("");
    meta.setScriptSource("FILE");
    meta.setScriptPath("process.py");
    meta.setParameters(List.of(new GraalPyParameter("number", "${N}")));
    meta.setOutputFields(List.of(field("value", "Integer", true)));
    try (PythonRuntimeSession session =
        new PythonRuntimeSession(
            meta,
            text ->
                text.equals("${PROJECT_HOME}") ? temp.toString() : text.equals("${N}") ? "7" : text,
            "file",
            0,
            (l, m) -> {})) {
      session.open();
      session.prepare(input());
      Files.writeString(file, "broken syntax !");
      assertEquals(7L, session.process(new Object[] {1L}, 1).rows().get(0)[0]);
    }
  }

  @Test
  void binaryRoundtripAndBytearraySnapshot() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            "def process(row, ctx):\n"
                + "    assert isinstance(row['bytes'], bytes)\n"
                + "    return {'bytes': bytearray(row['bytes']) + b'!'}");
    meta.setOutputFields(List.of(field("bytes", "Binary", true)));
    RowMeta metadata = new RowMeta();
    metadata.addValueMeta(new ValueMetaBinary("bytes"));
    byte[] bytes = {0, 1, -1};
    try (PythonRuntimeSession session = session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(metadata);
      Object[] out = session.process(new Object[] {bytes}, 1).rows().get(0);
      assertArrayEquals(new byte[] {0, 1, -1, 33}, (byte[]) out[0]);
      assertArrayEquals(new byte[] {0, 1, -1}, bytes);
    }
  }

  @Test
  void outputLimitAndPhaseErrors() throws Exception {
    GraalPyTransformMeta meta = meta("def process(row, ctx):\n    for i in range(3): ctx.emit({})");
    meta.setExecutionMode("EMIT_MANY");
    meta.setMaxEmittedRows(2);
    try (PythonRuntimeSession session = session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(input());
      Exception e = assertThrows(Exception.class, () -> session.process(new Object[] {1L}, 3));
      assertTrue(e.getMessage().contains("output limit"));
      assertTrue(e.getMessage().contains("row=3"));
    }
  }

  @Test
  void caughtOutputLimitStillFailsAndCaughtSkipStillFilters() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            "def process(row, ctx):\n"
                + "    try:\n"
                + "        ctx.emit({})\n"
                + "        ctx.emit({})\n"
                + "    except Exception: pass\n");
    meta.setExecutionMode("EMIT_MANY");
    meta.setMaxEmittedRows(1);
    try (PythonRuntimeSession session = session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(input());
      assertTrue(
          assertThrows(Exception.class, () -> session.process(new Object[] {1L}, 1))
              .getMessage()
              .contains("output limit"));
    }
    meta.setScriptText("def process(row, ctx):\n    try: ctx.skip()\n    except Exception: pass\n");
    try (PythonRuntimeSession session = session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(input());
      assertTrue(session.process(new Object[] {1L}, 1).rows().isEmpty());
    }
  }

  @Test
  void parametersAreReadOnlyAndCloseDoesNotMaskFailure() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
        def setup(ctx): ctx.parameters['x'] = 'changed'
        def process(row, ctx): return {}
        def close(ctx): raise RuntimeError('cleanup failed')
        """);
    List<String> logs = new ArrayList<>();
    try (PythonRuntimeSession session = session(meta, logs)) {
      session.open();
      Exception e = assertThrows(Exception.class, () -> session.prepare(input()));
      assertTrue(e.getMessage().contains("setup"));
    }
    assertTrue(logs.stream().anyMatch(message -> message.contains("cleanup failed")));
  }

  @Test
  void logFacadeAndStreamsShareBudget() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            "def process(row, ctx):\n"
                + "    ctx.log.info('x' * 500)\n"
                + "    print('y' * 500)\n"
                + "    return {}");
    meta.setMaxLogBytes(400);
    List<String> logs = new ArrayList<>();
    try (PythonRuntimeSession session = session(meta, logs)) {
      session.open();
      session.prepare(input());
      session.process(new Object[] {1L}, 1);
    }
    assertEquals(
        1, logs.stream().filter(message -> message.contains("further output suppressed")).count());
  }
}
