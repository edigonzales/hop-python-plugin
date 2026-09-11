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

  @HopMetadataProperty private int configurationVersion = 2;
  @HopMetadataProperty private String inputMode = "ALL";
  @HopMetadataProperty private String scriptSource = "INLINE";
  @HopMetadataProperty private String scriptPath = "";
  @HopMetadataProperty private long executionTimeoutSeconds = 60;
  @HopMetadataProperty private long maxEmittedRows = 10000;
  @HopMetadataProperty private long maxLogBytes = 1048576;
  @HopMetadataProperty private boolean nativeAccessEnabled = false;

  @HopMetadataProperty(groupKey = "selectedInputs", key = "field")
  private List<GraalPyInputField> selectedInputs = new ArrayList<>();

  @HopMetadataProperty(groupKey = "parameters", key = "parameter")
  private List<GraalPyParameter> parameters = new ArrayList<>();

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
    copyConfigurationFrom(other);
  }

  public void copyConfigurationFrom(GraalPyTransformMeta other) {
    selectedInputs.clear();
    parameters.clear();
    this.configurationVersion = other.configurationVersion;
    this.inputMode = other.inputMode;
    this.scriptSource = other.scriptSource;
    this.scriptPath = other.scriptPath;
    this.executionTimeoutSeconds = other.executionTimeoutSeconds;
    this.maxEmittedRows = other.maxEmittedRows;
    this.maxLogBytes = other.maxLogBytes;
    this.nativeAccessEnabled = other.nativeAccessEnabled;
    other.selectedInputs.forEach(f -> selectedInputs.add(new GraalPyInputField(f.getName())));
    other.parameters.forEach(p -> parameters.add(new GraalPyParameter(p.getName(), p.getValue())));
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
    configurationVersion = 2;
    inputMode = "ALL";
    scriptSource = "INLINE";
    scriptPath = "";
    executionTimeoutSeconds = 60;
    maxEmittedRows = 10000;
    maxLogBytes = 1048576;
    nativeAccessEnabled = false;
    selectedInputs = new ArrayList<>();
    parameters = new ArrayList<>();
    if (outputFields == null) {
      outputFields = new ArrayList<>();
    } else {
      outputFields.clear();
    }
    scriptText =
        "def process(row, ctx):\n"
            + "    # return None to filter a row or return a dict to produce output\n"
            + "    return {}\n";
    executionMode = GraalPyExecutionMode.RETURN_ONE.getCode();
    externalEnvironmentEnabled = false;
    graalPyVenvPath = "";
  }

  public void validate(IRowMeta inputRowMeta) throws HopTransformException {
    if ("INLINE".equals(scriptSource) && StringUtils.isBlank(scriptText)) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.EmptyScript"));
    }
    if (getExecutionModeEnum() == null) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransformMeta.CheckResult.ModeMissing"));
    }
    if (externalEnvironmentEnabled && StringUtils.isBlank(graalPyVenvPath)) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransformMeta.Exception.VenvPathMissing"));
    }
    if (outputFields == null) outputFields = new ArrayList<>();

    if (!List.of("INLINE", "FILE").contains(scriptSource)
        || !List.of("ALL", "SELECTED").contains(inputMode))
      throw new HopTransformException("Invalid script source or input mode");
    if ("FILE".equals(scriptSource) && StringUtils.isBlank(scriptPath))
      throw new HopTransformException("Script file path is required");
    if (executionTimeoutSeconds < 0 || maxEmittedRows < 0 || maxLogBytes < 0)
      throw new HopTransformException("Limits must be non-negative (0 means unlimited)");
    Set<String> parameterNames = new HashSet<>();
    for (GraalPyParameter p : parameters) {
      if (StringUtils.isBlank(p.getName()) || !parameterNames.add(p.getName()))
        throw new HopTransformException("Parameter names must be non-empty and unique");
    }
    Set<String> selectedNames = new HashSet<>();
    for (GraalPyInputField f : selectedInputs) {
      if (StringUtils.isBlank(f.getName()) || !selectedNames.add(f.getName()))
        throw new HopTransformException("Selected input names must be non-empty and unique");
      if ("SELECTED".equals(inputMode)
          && inputRowMeta != null
          && inputRowMeta.indexOfValue(f.getName()) < 0)
        throw new HopTransformException("Selected input field not found: " + f.getName());
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
      if (!GraalPyOutputField.isSupportedType(field.getHopType())) {
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
    return new GraalPyTransformDialog(
        parent, variables, transformMeta, pipelineMeta, transformName);
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

  @Override
  public boolean supportsErrorHandling() {
    return true;
  }

  @Override
  public void loadXml(org.w3c.dom.Node node, IHopMetadataProvider provider)
      throws org.apache.hop.core.exception.HopXmlException {
    super.loadXml(node, provider);
    if (org.apache.hop.core.xml.XmlHandler.getTagValue(node, "configurationVersion") == null) {
      configurationVersion = 2;
      inputMode = "ALL";
      scriptSource = "INLINE";
      scriptPath = "";
      selectedInputs = new ArrayList<>();
      parameters = new ArrayList<>();
      executionTimeoutSeconds = 0;
      maxEmittedRows = 0;
      maxLogBytes = 0;
      nativeAccessEnabled = externalEnvironmentEnabled;
    }
  }

  public int getConfigurationVersion() {
    return configurationVersion;
  }

  public void setConfigurationVersion(int value) {
    configurationVersion = value;
  }

  public String getInputMode() {
    return inputMode;
  }

  public void setInputMode(String value) {
    inputMode = value;
  }

  public String getScriptSource() {
    return scriptSource;
  }

  public void setScriptSource(String value) {
    scriptSource = value;
  }

  public String getScriptPath() {
    return scriptPath;
  }

  public void setScriptPath(String value) {
    scriptPath = value;
  }

  public long getExecutionTimeoutSeconds() {
    return executionTimeoutSeconds;
  }

  public void setExecutionTimeoutSeconds(long value) {
    executionTimeoutSeconds = value;
  }

  public long getMaxEmittedRows() {
    return maxEmittedRows;
  }

  public void setMaxEmittedRows(long value) {
    maxEmittedRows = value;
  }

  public long getMaxLogBytes() {
    return maxLogBytes;
  }

  public void setMaxLogBytes(long value) {
    maxLogBytes = value;
  }

  public boolean isNativeAccessEnabled() {
    return nativeAccessEnabled;
  }

  public void setNativeAccessEnabled(boolean value) {
    nativeAccessEnabled = value;
  }

  public List<GraalPyInputField> getSelectedInputs() {
    return selectedInputs;
  }

  public void setSelectedInputs(List<GraalPyInputField> value) {
    selectedInputs = new ArrayList<>(value);
  }

  public List<GraalPyParameter> getParameters() {
    return parameters;
  }

  public void setParameters(List<GraalPyParameter> value) {
    parameters = new ArrayList<>(value);
  }
}
