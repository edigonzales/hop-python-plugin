package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.io.ByteArrayOutputStream;
import org.apache.hop.core.BlockingRowSet;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.logging.LogLevel;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaBigNumber;
import org.apache.hop.core.row.value.ValueMetaBoolean;
import org.apache.hop.core.row.value.ValueMetaDate;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.core.row.value.ValueMetaNumber;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.pipeline.transforms.mock.TransformMockHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraalPyTransformRuntimeTest {
  @BeforeEach
  void initHop() throws Exception {
    HopEnvironment.init();
  }

  @AfterEach
  void resetHop() {
    HopEnvironment.reset();
  }

  @Test
  void returnOneTransformsRows() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                return {
                    "name": row["name"].upper(),
                    "greeting": "hello " + row["name"]
                }
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("name", "String", -1, -1, true),
            field("greeting", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    try (Harness harness = harness(meta, inputRowMeta, row("stefan"))) {
      List<Object[]> rows = harness.execute();

      assertEquals(1, rows.size());
      assertEquals("STEFAN", rows.get(0)[0]);
      assertEquals("hello stefan", rows.get(0)[1]);
    }
  }

  @Test
  void returnOneNoneFiltersRows() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                return None
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("ignored", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    try (Harness harness = harness(meta, inputRowMeta, row("stefan"))) {
      assertTrue(harness.execute().isEmpty());
    }
  }

  @Test
  void emitManySupportsZeroAndManyRowsPerInputRow() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                for i in range(row["count"]):
                    ctx.emit({"emitted": i})
                return None
            """,
            GraalPyExecutionMode.EMIT_MANY,
            field("emitted", "Integer", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaInteger("count"));

    try (Harness harness = harness(meta, inputRowMeta, row(0L), row(2L))) {
      List<Object[]> rows = harness.execute();

      assertEquals(2, rows.size());
      assertEquals(2L, rows.get(0)[0]);
      assertEquals(0L, rows.get(0)[1]);
      assertEquals(2L, rows.get(1)[0]);
      assertEquals(1L, rows.get(1)[1]);
    }
  }

  @Test
  void replaceExistingFieldsKeepInputValueWhenMissing() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                return {}
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("name", "String", -1, -1, true));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    try (Harness harness = harness(meta, inputRowMeta, row("original"))) {
      List<Object[]> rows = harness.execute();

      assertEquals(1, rows.size());
      assertEquals("original", rows.get(0)[0]);
    }
  }

  @Test
  void appendFieldsBecomeNullWhenMissing() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                return {}
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("extra", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    try (Harness harness = harness(meta, inputRowMeta, row("original"))) {
      List<Object[]> rows = harness.execute();

      assertEquals(1, rows.size());
      assertEquals("original", rows.get(0)[0]);
      assertNull(rows.get(0)[1]);
    }
  }

  @Test
  void unknownOutputFieldsFailFast() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                return {"unknown": "boom"}
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("known", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    try (Harness harness = harness(meta, inputRowMeta, row("value"))) {
      assertThrows(HopTransformException.class, harness::execute);
    }
  }

  @Test
  void skipDropsCurrentRow() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                ctx.skip()
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("ignored", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    try (Harness harness = harness(meta, inputRowMeta, row("value"))) {
      assertTrue(harness.execute().isEmpty());
    }
  }

  @Test
  void abortFailsFast() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                ctx.abort("stop now")
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("ignored", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    try (Harness harness = harness(meta, inputRowMeta, row("value"))) {
      HopTransformException exception =
          assertThrows(HopTransformException.class, harness::execute);
      assertTrue(exception.getMessage().contains("stop now"));
    }
  }

  @Test
  void emitSnapshotsMappingsImmediately() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                out = {"value": row["value"]}
                ctx.emit(out)
                out["value"] = "mutated"
                return None
            """,
            GraalPyExecutionMode.EMIT_MANY,
            field("value", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("value"));

    try (Harness harness = harness(meta, inputRowMeta, row("first"))) {
      List<Object[]> rows = harness.execute();

      assertEquals(1, rows.size());
      assertEquals("first", rows.get(0)[0]);
      assertEquals("first", rows.get(0)[1]);
    }
  }

  @Test
  void typeBridgeRoundTripsSupportedTypes() throws Exception {
    Date timestamp = new Date(1_710_000_123_000L);
    Date midnight = Date.from(LocalDate.of(2024, 1, 2).atStartOfDay(ZoneId.systemDefault()).toInstant());
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                date_module = __import__("datetime")
                decimal_module = __import__("decimal")
                return {
                    "text": row["text"] + "-ok",
                    "count": row["count"] + 1,
                    "ratio": row["ratio"] + 0.5,
                    "amount": row["amount"] + decimal_module.Decimal("0.99"),
                    "flag": not row["flag"],
                    "created_at": row["created_at"],
                    "day": date_module.date(2024, 1, 2),
                }
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("text", "String", -1, -1, true),
            field("count", "Integer", -1, -1, true),
            field("ratio", "Number", -1, -1, true),
            field("amount", "BigNumber", -1, -1, true),
            field("flag", "Boolean", -1, -1, true),
            field("created_at", "Date", -1, -1, true),
            field("day", "Date", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("text"));
    inputRowMeta.addValueMeta(new ValueMetaInteger("count"));
    inputRowMeta.addValueMeta(new ValueMetaNumber("ratio"));
    inputRowMeta.addValueMeta(new ValueMetaBigNumber("amount"));
    inputRowMeta.addValueMeta(new ValueMetaBoolean("flag"));
    inputRowMeta.addValueMeta(new ValueMetaDate("created_at"));

    try (Harness harness =
        harness(meta, inputRowMeta, row("abc", 7L, 1.25D, new BigDecimal("99.01"), true, timestamp))) {
      List<Object[]> rows = harness.execute();

      assertEquals(1, rows.size());
      Object[] row = rows.get(0);
      assertEquals("abc-ok", row[0]);
      assertEquals(8L, row[1]);
      assertEquals(1.75D, (Double) row[2], 0.00001D);
      assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) row[3]));
      assertEquals(false, row[4]);
      assertEquals(timestamp, row[5]);
      assertEquals(midnight, row[6]);
    }
  }

  @Test
  void javaTypeFileIoAndSubprocessAreBlocked() throws Exception {
    assertSecurityFailure(
        """
        def process(row, ctx):
            return {"out": java.type("java.lang.System").getProperty("user.home")}
        """);
    assertSecurityFailure(
        """
        def process(row, ctx):
            return {"out": open("pom.xml").read()}
        """);
    assertSecurityFailure(
        """
        def process(row, ctx):
            return {"out": __import__("subprocess").check_output(["echo", "x"]).decode()}
        """);
  }

  @Test
  void environmentVariablesAreNotExposed() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                return {"out": __import__("os").getenv("HOME")}
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("out", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("input"));

    try (Harness harness = harness(meta, inputRowMeta, row("value"))) {
      List<Object[]> rows = harness.execute();

      assertEquals(1, rows.size());
      assertNull(rows.get(0)[1]);
    }
  }

  @Test
  void eachTransformCopyOwnsItsOwnContext() throws Exception {
    GraalPyTransformMeta meta =
        meta(
            """
            def process(row, ctx):
                return {"out": row["value"]}
            """,
            GraalPyExecutionMode.RETURN_ONE,
            field("out", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("value"));

    try (Harness first = harness(meta.clone(), inputRowMeta, row("a"));
        Harness second = harness(meta.clone(), inputRowMeta, row("b"))) {
      assertNotNull(first.data.context);
      assertNotNull(second.data.context);
      assertNotSame(first.data.context, second.data.context);
    }
  }

  private void assertSecurityFailure(String script) throws Exception {
    GraalPyTransformMeta meta =
        meta(script, GraalPyExecutionMode.RETURN_ONE, field("out", "String", -1, -1, false));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("input"));

    try (Harness harness = harness(meta, inputRowMeta, row("value"))) {
      assertThrows(HopTransformException.class, harness::execute);
    }
  }

  private static GraalPyTransformMeta meta(
      String script, GraalPyExecutionMode mode, GraalPyOutputField... outputFields) {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setScriptText(script);
    meta.setExecutionMode(mode.getCode());
    meta.setOutputFields(List.of(outputFields));
    return meta;
  }

  private static GraalPyOutputField field(
      String name, String type, int length, int precision, boolean replaceExisting) {
    GraalPyOutputField field = new GraalPyOutputField();
    field.setName(name);
    field.setType(type);
    field.setLength(length);
    field.setPrecision(precision);
    field.setReplaceExisting(replaceExisting);
    return field;
  }

  private static Object[] row(Object... values) {
    return values;
  }

  private static Harness harness(GraalPyTransformMeta meta, RowMeta inputRowMeta, Object[]... rows)
      throws Exception {
    return new Harness(meta, inputRowMeta, List.of(rows));
  }

  private static final class Harness implements AutoCloseable {
    private final TransformMockHelper<GraalPyTransformMeta, GraalPyTransformData> helper;
    private final GraalPyTransform transform;
    private final GraalPyTransformData data;
    private final ByteArrayOutputStream logBuffer;

    private Harness(GraalPyTransformMeta meta, RowMeta inputRowMeta, List<Object[]> rows)
        throws Exception {
      helper =
          new TransformMockHelper<>(
              "GraalPyTransformRuntimeTest", GraalPyTransformMeta.class, GraalPyTransformData.class);
      logBuffer = new ByteArrayOutputStream();
      helper.redirectLog(logBuffer, LogLevel.BASIC);
      helper.pipeline.setRunning(true);
      data = new GraalPyTransformData();
      transform = new GraalPyTransform(helper.transformMeta, meta, data, 0, helper.pipelineMeta, helper.pipeline);
      transform.setInputRowMeta(inputRowMeta);
      transform.setInputRowSets(new ArrayList<>(List.of(helper.getMockInputRowSet(rows))));
      assertTrue(transform.init(), logBuffer.toString());
    }

    private List<Object[]> execute() throws Exception {
      BlockingRowSet outputRowSet = new BlockingRowSet(20);
      transform.setOutputRowSets(new ArrayList<>(List.of(outputRowSet)));

      List<Object[]> rows = new ArrayList<>();
      int iterations = 0;
      while (transform.processRow()) {
        drain(outputRowSet, rows);
        iterations++;
        if (iterations > 100) {
          throw new AssertionError("Transform did not terminate");
        }
      }
      drain(outputRowSet, rows);
      return rows;
    }

    private void drain(BlockingRowSet outputRowSet, List<Object[]> rows) {
      Object[] row;
      while ((row = outputRowSet.getRowImmediate()) != null) {
        rows.add(row);
      }
    }

    @Override
    public void close() {
      transform.dispose();
      helper.cleanUp();
    }
  }
}
