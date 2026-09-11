package ch.so.agi.hop.python.transform.graalpy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.hop.core.CheckResult;
import org.apache.hop.core.ICheckResult;
import org.apache.hop.core.annotations.Transform;
import org.apache.hop.core.exception.HopPluginException;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.util.Utils;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.BaseTransformMeta;
import org.apache.hop.pipeline.transform.ITransformDialog;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.eclipse.swt.widgets.Shell;

@Transform(
    id = "GraalPyTransform",
    image = "ch/so/agi/hop/python/transform/graalpy/icons/graalpy.svg",
    name = "GraalPy",
    description = "Executes Python scripts using GraalPy",
    categoryDescription = "Scripting",
    keywords = {"python", "graalpy", "script"},
    documentationUrl = "")
public class GraalPyTransformMeta extends BaseTransformMeta<GraalPyTransform, GraalPyTransformData>
    implements ITransformMeta {
  private static final Class<?> PKG = GraalPyTransformMeta.class;

  @HopMetadataProperty private String scriptText;
  @HopMetadataProperty private String executionMode;
  @HopMetadataProperty private boolean externalEnvironmentEnabled;
  @HopMetadataProperty private String graalPyVenvPath;
  @HopMetadataProperty(groupKey = "outputFields", key = "outputField")
  private List<GraalPyOutputField> outputFields;

  public GraalPyTransformMeta() {
    outputFields = new ArrayList<>();
    setDefault();
  }

  public GraalPyTransformMeta(GraalPyTransformMeta other) {
    this();
    this.scriptText = other.scriptText;
    this.executionMode = other.executionMode;
    this.externalEnvironmentEnabled = other.externalEnvironmentEnabled;
    this.graalPyVenvPath = other.graalPyVenvPath;
    this.outputFields.clear();
    other.outputFields.forEach(field -> this.outputFields.add(new GraalPyOutputField(field)));
  }

  @Override
  public GraalPyTransformMeta clone() {
    return new GraalPyTransformMeta(this);
  }

  @Override
  public void setDefault() {
    if (outputFields == null) {
      outputFields = new ArrayList<>();
    } else {
      outputFields.clear();
    }
    scriptText =
        "def process(row, ctx):\n"
            + "    # return None to filter a row or return a dict to produce output\n"
            + "    return row\n";
    executionMode = GraalPyExecutionMode.RETURN_ONE.getCode();
    externalEnvironmentEnabled = false;
    graalPyVenvPath = "";
  }

  public void validate(IRowMeta inputRowMeta) throws HopTransformException {
    if (StringUtils.isBlank(scriptText)) {
      throw new HopTransformException(BaseMessages.getString(PKG, "GraalPyTransform.Exception.EmptyScript"));
    }
    if (getExecutionModeEnum() == null) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransformMeta.CheckResult.ModeMissing"));
    }
    if (externalEnvironmentEnabled && StringUtils.isBlank(graalPyVenvPath)) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransformMeta.Exception.VenvPathMissing"));
    }
    if (outputFields == null || outputFields.isEmpty()) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransformMeta.CheckResult.NoOutputFields"));
    }

    Set<String> names = new HashSet<>();
    for (GraalPyOutputField field : outputFields) {
      if (StringUtils.isBlank(field.getName())) {
        throw new HopTransformException(
            BaseMessages.getString(PKG, "GraalPyTransformMeta.Exception.FieldMissing"));
      }
      if (!names.add(field.getName())) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG, "GraalPyTransformMeta.Exception.DuplicateField", field.getName()));
      }
      if (!GraalPyOutputField.SUPPORTED_TYPES.contains(field.getHopType())) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG,
                "GraalPyTransformMeta.Exception.UnsupportedType",
                field.getType(),
                field.getName()));
      }
      if (field.isReplaceExisting()
          && inputRowMeta != null
          && inputRowMeta.indexOfValue(field.getName()) < 0) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG, "GraalPyTransformMeta.Exception.ReplaceFieldMissing", field.getName()));
      }
    }
  }

  @Override
  public void getFields(
      IRowMeta rowMeta,
      String transformName,
      IRowMeta[] info,
      TransformMeta nextTransform,
      IVariables variables,
      IHopMetadataProvider metadataProvider)
      throws HopTransformException {
    IRowMeta safeRowMeta = rowMeta == null ? new RowMeta() : rowMeta;
    validate(safeRowMeta);

    for (GraalPyOutputField field : outputFields) {
      try {
        IValueMeta valueMeta = field.createValueMeta();
        valueMeta.setOrigin(transformName);
        if (field.isReplaceExisting()) {
          safeRowMeta.setValueMeta(safeRowMeta.indexOfValue(field.getName()), valueMeta);
        } else {
          safeRowMeta.addValueMeta(valueMeta);
        }
      } catch (HopPluginException e) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG,
                "GraalPyTransformMeta.Exception.UnsupportedType",
                field.getType(),
                field.getName()),
            e);
      }
    }
  }

  @Override
  public void check(
      List<ICheckResult> remarks,
      PipelineMeta pipelineMeta,
      TransformMeta transformMeta,
      IRowMeta prev,
      String[] input,
      String[] output,
      IRowMeta info,
      IVariables variables,
      IHopMetadataProvider metadataProvider) {
    try {
      validate(prev);
      if (Utils.isEmpty(input)) {
        remarks.add(
            new CheckResult(
                ICheckResult.TYPE_RESULT_WARNING,
                BaseMessages.getString(PKG, "GraalPyTransformMeta.CheckResult.NoInput"),
                transformMeta));
      } else {
        remarks.add(
            new CheckResult(
                ICheckResult.TYPE_RESULT_OK,
                BaseMessages.getString(PKG, "GraalPyTransformMeta.CheckResult.Ok"),
                transformMeta));
      }
    } catch (HopTransformException e) {
      remarks.add(new CheckResult(ICheckResult.TYPE_RESULT_ERROR, e.getMessage(), transformMeta));
    }
  }

  public ITransformDialog getDialog(
      Shell parent,
      IVariables variables,
      ITransformMeta transformMeta,
      PipelineMeta pipelineMeta,
      String transformName) {
    return new GraalPyTransformDialog(parent, variables, transformMeta, pipelineMeta, transformName);
  }

  public String getScriptText() {
    return scriptText;
  }

  public void setScriptText(String scriptText) {
    this.scriptText = scriptText;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  public void setExecutionMode(String executionMode) {
    this.executionMode = executionMode;
  }

  public GraalPyExecutionMode getExecutionModeEnum() {
    return GraalPyExecutionMode.fromCode(executionMode);
  }

  public boolean isExternalEnvironmentEnabled() {
    return externalEnvironmentEnabled;
  }

  public void setExternalEnvironmentEnabled(boolean externalEnvironmentEnabled) {
    this.externalEnvironmentEnabled = externalEnvironmentEnabled;
  }

  public String getGraalPyVenvPath() {
    return graalPyVenvPath;
  }

  public void setGraalPyVenvPath(String graalPyVenvPath) {
    this.graalPyVenvPath = graalPyVenvPath;
  }

  public List<GraalPyOutputField> getOutputFields() {
    return outputFields;
  }

  public void setOutputFields(List<GraalPyOutputField> outputFields) {
    this.outputFields = outputFields == null ? new ArrayList<>() : new ArrayList<>(outputFields);
  }
}
