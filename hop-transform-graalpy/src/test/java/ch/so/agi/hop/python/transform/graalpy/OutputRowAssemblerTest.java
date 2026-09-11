package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.junit.jupiter.api.Test;

class OutputRowAssemblerTest {
  @Test
  void copiesEvenOversizedInputAndPreservesMissingVersusNull() {
    RowMeta meta = new RowMeta();
    meta.addValueMeta(new ValueMetaInteger("value"));
    GraalPyOutputField field = new GraalPyOutputField();
    field.setName("value");
    field.setReplaceExisting(true);
    OutputRowAssembler assembler = new OutputRowAssembler(meta, meta, List.of(field));
    Object[] input = {9L, "spare", null};
    Object[] first = assembler.createOutputRow(input, Map.of("value", 1L));
    Object[] second = assembler.createOutputRow(input, Map.of("value", 2L));
    assertNotSame(first, second);
    assertNotSame(input, first);
    assertArrayEquals(new Object[] {1L}, first);
    assertArrayEquals(new Object[] {2L}, second);
    assertEquals(9L, input[0]);
    assertEquals(9L, assembler.createOutputRow(input, Map.of())[0]);
    assertNull(
        assembler.createOutputRow(input, java.util.Collections.singletonMap("value", null))[0]);
  }
}
