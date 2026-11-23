package ch.so.agi.hop.python.transform.graalpy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;

public final class OutputRowAssembler {
  private final IRowMeta outputRowMeta;
  private final List<FieldBinding> bindings;
  private final Map<String, IValueMeta> valueMetas = new LinkedHashMap<>();

  public OutputRowAssembler(
      IRowMeta inputRowMeta, IRowMeta outputRowMeta, List<GraalPyOutputField> outputFields) {
    this.outputRowMeta = outputRowMeta;
    this.bindings = new ArrayList<>();

    int appendIndex = inputRowMeta == null ? 0 : inputRowMeta.size();
    for (GraalPyOutputField field : outputFields) {
      int inputIndex = inputRowMeta == null ? -1 : inputRowMeta.indexOfValue(field.getName());
      int outputIndex = field.isReplaceExisting() ? inputIndex : appendIndex++;
      bindings.add(new FieldBinding(field, outputIndex));
      valueMetas.put(field.getName(), outputRowMeta.getValueMeta(outputIndex));
    }
  }

  public Object[] createOutputRow(Object[] inputRow, Map<String, Object> values) {
    Object[] outputRow = java.util.Arrays.copyOf(inputRow, outputRowMeta.size());
    for (FieldBinding binding : bindings) {
      if (binding.field.isReplaceExisting()) {
        if (values.containsKey(binding.field.getName())) {
          outputRow[binding.outputIndex] = values.get(binding.field.getName());
        }
      } else {
        outputRow[binding.outputIndex] = values.get(binding.field.getName());
      }
    }
    return outputRow;
  }

  public IValueMeta getOutputValueMeta(String fieldName) {
    return valueMetas.get(fieldName);
  }

  public List<Map<String, Object>> buildFieldMetadata(IRowMeta inputRowMeta) {
    List<Map<String, Object>> metadata = new ArrayList<>();
    if (inputRowMeta == null) {
      return metadata;
    }
    for (int i = 0; i < inputRowMeta.size(); i++) {
      IValueMeta valueMeta = inputRowMeta.getValueMeta(i);
      Map<String, Object> field = new LinkedHashMap<>();
      field.put("name", valueMeta.getName());
      field.put("type", valueMeta.getTypeDesc());
      field.put("length", valueMeta.getLength());
      field.put("precision", valueMeta.getPrecision());
      metadata.add(Collections.unmodifiableMap(field));
    }
    return Collections.unmodifiableList(metadata);
  }

  private record FieldBinding(GraalPyOutputField field, int outputIndex) {}
}
