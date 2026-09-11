package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.core.xml.XmlHandler;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraalPyTransformMetaTest {
  @BeforeEach
  void initHop() throws Exception {
    HopEnvironment.init();
  }

  @AfterEach
  void resetHop() {
    HopEnvironment.reset();
  }

  @Test
  void xmlRoundTripPreservesConfiguration() throws Exception {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setScriptText(
        "def process(row, ctx):\n"
            + "    ctx.log.info('hello')\n"
            + "    return {'value': row['value'], 'extra': 'x'}\n");
    meta.setExecutionMode(GraalPyExecutionMode.EMIT_MANY.getCode());
    meta.setExternalEnvironmentEnabled(true);
    meta.setGraalPyVenvPath("${GRAALPY_VENV}");
    meta.setOutputFields(
        List.of(field("value", "Integer", -1, -1, true), field("extra", "String", 255, -1, false)));

    String xml =
        "<" + TransformMeta.XML_TAG + ">" + meta.getXml() + "</" + TransformMeta.XML_TAG + ">";

    GraalPyTransformMeta copy = new GraalPyTransformMeta();
    copy.loadXml(XmlHandler.loadXmlString(xml, TransformMeta.XML_TAG), null);

    assertEquals(meta.getScriptText(), copy.getScriptText());
    assertEquals(meta.getExecutionMode(), copy.getExecutionMode());
    assertEquals(meta.isExternalEnvironmentEnabled(), copy.isExternalEnvironmentEnabled());
    assertEquals(meta.getGraalPyVenvPath(), copy.getGraalPyVenvPath());
    assertEquals(meta.getOutputFields().size(), copy.getOutputFields().size());
    assertEquals("value", copy.getOutputFields().get(0).getName());
    assertEquals("Integer", copy.getOutputFields().get(0).getType());
    assertEquals(true, copy.getOutputFields().get(0).isReplaceExisting());
    assertEquals("extra", copy.getOutputFields().get(1).getName());
    assertEquals("String", copy.getOutputFields().get(1).getType());
    assertEquals(255, copy.getOutputFields().get(1).getLength());
  }

  @Test
  void getFieldsAppliesReplaceAndAppendSemantics() throws Exception {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setOutputFields(
        List.of(
            field("count", "Integer", -1, -1, true), field("message", "String", 128, -1, false)));

    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("name"));
    rowMeta.addValueMeta(new ValueMetaInteger("count"));

    meta.getFields(rowMeta, "GraalPy", null, null, null, null);

    assertEquals(3, rowMeta.size());
    assertEquals("name", rowMeta.getValueMeta(0).getName());
    assertEquals("count", rowMeta.getValueMeta(1).getName());
    assertEquals("Integer", rowMeta.getValueMeta(1).getTypeDesc());
    assertEquals("message", rowMeta.getValueMeta(2).getName());
    assertEquals("String", rowMeta.getValueMeta(2).getTypeDesc());
    assertEquals(128, rowMeta.getValueMeta(2).getLength());
  }

  @Test
  void validateRejectsDuplicateFields() {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setOutputFields(
        List.of(field("value", "String", -1, -1, false), field("value", "String", -1, -1, false)));

    assertThrows(HopTransformException.class, () -> meta.validate(new RowMeta()));
  }

  @Test
  void validateRejectsMissingReplaceField() {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setOutputFields(List.of(field("missing", "String", -1, -1, true)));

    RowMeta inputRowMeta = new RowMeta();
    inputRowMeta.addValueMeta(new ValueMetaString("name"));

    assertThrows(HopTransformException.class, () -> meta.validate(inputRowMeta));
  }

  @Test
  void validateRejectsMissingVenvPathWhenExternalEnvironmentIsEnabled() {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setExternalEnvironmentEnabled(true);
    meta.setGraalPyVenvPath("");
    meta.setOutputFields(List.of(field("value", "String", -1, -1, false)));

    assertThrows(HopTransformException.class, () -> meta.validate(new RowMeta()));
  }

  @Test
  void newConfigurationRoundTripsAndCloneIsIndependent() throws Exception {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setInputMode("SELECTED");
    meta.setSelectedInputs(List.of(new GraalPyInputField("value")));
    meta.setParameters(List.of(new GraalPyParameter("factor", "${FACTOR}")));
    meta.setScriptSource("FILE");
    meta.setScriptPath("${PROJECT_HOME}/code.py");
    meta.setExecutionTimeoutSeconds(4);
    meta.setMaxEmittedRows(50);
    meta.setMaxLogBytes(2048);
    String xml = "<transform>" + meta.getXml() + "</transform>";
    GraalPyTransformMeta loaded = new GraalPyTransformMeta();
    loaded.loadXml(XmlHandler.loadXmlString(xml, "transform"), null);
    assertEquals("SELECTED", loaded.getInputMode());
    assertEquals("value", loaded.getSelectedInputs().get(0).getName());
    assertEquals("${FACTOR}", loaded.getParameters().get(0).getValue());
    assertEquals("FILE", loaded.getScriptSource());
    assertEquals(4, loaded.getExecutionTimeoutSeconds());
    assertEquals(50, loaded.getMaxEmittedRows());
    assertEquals(2048, loaded.getMaxLogBytes());
    GraalPyTransformMeta clone = loaded.clone();
    clone.getParameters().get(0).setValue("different");
    assertEquals("${FACTOR}", loaded.getParameters().get(0).getValue());
  }

  @Test
  void legacyXmlKeepsLimitsOffAndNativeAccess() throws Exception {
    GraalPyTransformMeta meta = new GraalPyTransformMeta();
    meta.setExternalEnvironmentEnabled(true);
    meta.setGraalPyVenvPath("venv");
    String xml =
        ("<transform>" + meta.getXml() + "</transform>")
            .replaceAll("<configurationVersion>.*?</configurationVersion>", "");
    GraalPyTransformMeta loaded = new GraalPyTransformMeta();
    loaded.loadXml(XmlHandler.loadXmlString(xml, "transform"), null);
    assertEquals(0, loaded.getExecutionTimeoutSeconds());
    assertEquals(0, loaded.getMaxEmittedRows());
    assertEquals(0, loaded.getMaxLogBytes());
    assertTrue(loaded.isNativeAccessEnabled());
    assertEquals("INLINE", loaded.getScriptSource());
    assertEquals(60, new GraalPyTransformMeta().getExecutionTimeoutSeconds());
    assertFalse(new GraalPyTransformMeta().isNativeAccessEnabled());
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
}
