package ch.so.agi.hop.python.transform.graalpy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;

public final class OutputRowAssembler {
  private final IRowMeta outputRowMeta;
  private final List<FieldBinding> bindings;
  private final Set<String> allowedFields;
  private final int inputSize;
  private final Map<String, IValueMeta> valueMetas = new LinkedHashMap<>();

  public OutputRowAssembler(
      IRowMeta inputRowMeta, IRowMeta outputRowMeta, List<GraalPyOutputField> outputFields) {
    this.outputRowMeta = outputRowMeta;
    this.inputSize = inputRowMeta == null ? 0 : inputRowMeta.size();
    this.bindings = new ArrayList<>();
    this.allowedFields = new LinkedHashSet<>();

    int appendIndex = inputSize;
    for (GraalPyOutputField field : outputFields) {
      int inputIndex = inputRowMeta == null ? -1 : inputRowMeta.indexOfValue(field.getName());
      int outputIndex = field.isReplaceExisting() ? inputIndex : appendIndex++;
      IValueMeta valueMeta = outputRowMeta.getValueMeta(outputIndex);
      bindings.add(new FieldBinding(field, outputIndex, valueMeta));
      allowedFields.add(field.getName());
      valueMetas.put(field.getName(), valueMeta);
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

  public Set<String> getAllowedFields() {
    return Collections.unmodifiableSet(allowedFields);
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

  public IRowMeta getOutputRowMeta() {
    return outputRowMeta;
  }

  private record FieldBinding(GraalPyOutputField field, int outputIndex, IValueMeta valueMeta) {}
}
