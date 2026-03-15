package ch.so.agi.hop.python.transform.graalpy;

import java.util.ArrayList;
import java.util.List;
import org.apache.hop.core.Const;
import org.apache.hop.core.Props;
import org.apache.hop.core.util.Utils;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.dialog.BaseDialog;
import org.apache.hop.ui.core.widget.ColumnInfo;
import org.apache.hop.ui.core.widget.ScriptStyledTextComp;
import org.apache.hop.ui.core.widget.TableView;
import org.apache.hop.ui.pipeline.transform.BaseTransformDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TableItem;

public class GraalPyTransformDialog extends BaseTransformDialog {
  private static final Class<?> PKG = GraalPyTransformDialog.class;
  private static final String[] YES_NO_COMBO =
      new String[] {
        BaseMessages.getString(PKG, "System.Combo.No"),
        BaseMessages.getString(PKG, "System.Combo.Yes")
      };

  private final GraalPyTransformMeta input;

  private CCombo wMode;
  private ScriptStyledTextComp wScript;
  private TableView wFields;
  private ModifyListener lsMod;
  private int middle;
  private int margin;
  private boolean loading;

  public GraalPyTransformDialog(
      Shell parent,
      IVariables variables,
      GraalPyTransformMeta transformMeta,
      PipelineMeta pipelineMeta) {
    super(parent, variables, transformMeta, pipelineMeta);
    input = transformMeta;
  }

  public GraalPyTransformDialog(
      Shell parent,
      IVariables variables,
      Object transformMeta,
      PipelineMeta pipelineMeta,
      String transformName) {
    this(parent, variables, castTransformMeta(transformMeta), pipelineMeta, transformName);
  }

  public GraalPyTransformDialog(
      Shell parent,
      IVariables variables,
      ITransformMeta transformMeta,
      PipelineMeta pipelineMeta,
      String transformName) {
    super(parent, variables, transformMeta, pipelineMeta, transformName);
    input = castTransformMeta(transformMeta);
  }

  private static GraalPyTransformMeta castTransformMeta(Object transformMeta) {
    if (transformMeta instanceof GraalPyTransformMeta graalPyTransformMeta) {
      return graalPyTransformMeta;
    }
    throw new IllegalArgumentException(
        "Expected GraalPyTransformMeta but got "
            + (transformMeta == null ? "null" : transformMeta.getClass().getName()));
  }

  @Override
  public String open() {
    Shell parent = getParent();
    shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.MIN);
    PropsUi.setLook(shell);
    setShellImage(shell, input);
    shell.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Title"));

    middle = props.getMiddlePct();
    margin = PropsUi.getMargin();

    FormLayout formLayout = new FormLayout();
    formLayout.marginWidth = margin;
    formLayout.marginHeight = margin;
    shell.setLayout(formLayout);

    lsMod =
        e -> {
          if (!loading) {
            input.setChanged();
          }
        };
    changed = input.hasChanged();

    wlTransformName = new Label(shell, SWT.RIGHT);
    wlTransformName.setText(BaseMessages.getString(PKG, "System.Label.TransformName"));
    PropsUi.setLook(wlTransformName);
    fdlTransformName = new FormData();
    fdlTransformName.left = new FormAttachment(0, 0);
    fdlTransformName.top = new FormAttachment(0, 0);
    fdlTransformName.right = new FormAttachment(middle, -margin);
    wlTransformName.setLayoutData(fdlTransformName);

    wTransformName = new org.eclipse.swt.widgets.Text(shell, SWT.SINGLE | SWT.LEFT | SWT.BORDER);
    PropsUi.setLook(wTransformName);
    wTransformName.addModifyListener(lsMod);
    fdTransformName = new FormData();
    fdTransformName.left = new FormAttachment(middle, 0);
    fdTransformName.top = new FormAttachment(0, 0);
    fdTransformName.right = new FormAttachment(100, 0);
    wTransformName.setLayoutData(fdTransformName);

    Label wlMode = new Label(shell, SWT.RIGHT);
    wlMode.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Mode.Label"));
    wlMode.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Mode.Tooltip"));
    PropsUi.setLook(wlMode);
    FormData fdlMode = new FormData();
    fdlMode.left = new FormAttachment(0, 0);
    fdlMode.right = new FormAttachment(middle, -margin);
    fdlMode.top = new FormAttachment(wTransformName, margin);
    wlMode.setLayoutData(fdlMode);

    wMode = new CCombo(shell, SWT.BORDER | SWT.READ_ONLY);
    wMode.setItems(
        new String[] {
          BaseMessages.getString(PKG, "GraalPyTransformDialog.Mode.ReturnOne"),
          BaseMessages.getString(PKG, "GraalPyTransformDialog.Mode.EmitMany")
        });
    wMode.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Mode.Tooltip"));
    PropsUi.setLook(wMode);
    wMode.addModifyListener(lsMod);
    FormData fdMode = new FormData();
    fdMode.left = new FormAttachment(middle, 0);
    fdMode.top = new FormAttachment(wTransformName, margin);
    fdMode.right = new FormAttachment(100, 0);
    wMode.setLayoutData(fdMode);

    Label wlHelp = new Label(shell, SWT.WRAP);
    wlHelp.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Help.Label"));
    PropsUi.setLook(wlHelp);
    FormData fdlHelp = new FormData();
    fdlHelp.left = new FormAttachment(0, 0);
    fdlHelp.top = new FormAttachment(wMode, margin);
    fdlHelp.right = new FormAttachment(100, 0);
    wlHelp.setLayoutData(fdlHelp);

    SashForm sashForm = new SashForm(shell, SWT.VERTICAL);
    PropsUi.setLook(sashForm);
    FormData fdSash = new FormData();
    fdSash.left = new FormAttachment(0, 0);
    fdSash.top = new FormAttachment(wlHelp, margin);
    fdSash.right = new FormAttachment(100, 0);
    sashForm.setLayoutData(fdSash);

    Composite wScriptComp = new Composite(sashForm, SWT.NONE);
    PropsUi.setLook(wScriptComp);
    wScriptComp.setLayout(props.createFormLayout());

    Label wlScript = new Label(wScriptComp, SWT.NONE);
    wlScript.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Label"));
    wlScript.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Tooltip"));
    PropsUi.setLook(wlScript);
    FormData fdlScript = new FormData();
    fdlScript.left = new FormAttachment(0, 0);
    fdlScript.top = new FormAttachment(0, 0);
    wlScript.setLayoutData(fdlScript);

    wScript =
        new ScriptStyledTextComp(
            variables,
            wScriptComp,
            SWT.MULTI | SWT.LEFT | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL,
            false);
    wScript.addLineStyleListener(new PythonCodeHighlight());
    wScript.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Tooltip"));
    wScript.addModifyListener(lsMod);
    PropsUi.setLook(wScript, Props.WIDGET_STYLE_FIXED);
    FormData fdScript = new FormData();
    fdScript.left = new FormAttachment(0, 0);
    fdScript.top = new FormAttachment(wlScript, margin);
    fdScript.right = new FormAttachment(100, 0);
    fdScript.bottom = new FormAttachment(100, 0);
    wScript.setLayoutData(fdScript);

    Composite wFieldsComp = new Composite(sashForm, SWT.NONE);
    PropsUi.setLook(wFieldsComp);
    wFieldsComp.setLayout(props.createFormLayout());

    Label wlFields = new Label(wFieldsComp, SWT.NONE);
    wlFields.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Fields.Label"));
    wlFields.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Fields.Tooltip"));
    PropsUi.setLook(wlFields);
    FormData fdlFields = new FormData();
    fdlFields.left = new FormAttachment(0, 0);
    fdlFields.top = new FormAttachment(0, 0);
    wlFields.setLayoutData(fdlFields);

    ColumnInfo[] columns = new ColumnInfo[5];
    columns[0] =
        new ColumnInfo(
            BaseMessages.getString(PKG, "GraalPyTransformDialog.Column.Name"),
            ColumnInfo.COLUMN_TYPE_TEXT);
    columns[1] =
        new ColumnInfo(
            BaseMessages.getString(PKG, "GraalPyTransformDialog.Column.Type"),
            ColumnInfo.COLUMN_TYPE_CCOMBO,
            GraalPyOutputField.supportedTypeNames());
    columns[2] =
        new ColumnInfo(
            BaseMessages.getString(PKG, "GraalPyTransformDialog.Column.Length"),
            ColumnInfo.COLUMN_TYPE_TEXT);
    columns[3] =
        new ColumnInfo(
            BaseMessages.getString(PKG, "GraalPyTransformDialog.Column.Precision"),
            ColumnInfo.COLUMN_TYPE_TEXT);
    columns[4] =
        new ColumnInfo(
            BaseMessages.getString(PKG, "GraalPyTransformDialog.Column.Replace"),
            ColumnInfo.COLUMN_TYPE_CCOMBO,
            YES_NO_COMBO);

    wFields =
        new TableView(
            variables,
            wFieldsComp,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL,
            columns,
            Math.max(1, input.getOutputFields().size()),
            lsMod,
            props);
    wFields.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Fields.Tooltip"));
    FormData fdFields = new FormData();
    fdFields.left = new FormAttachment(0, 0);
    fdFields.top = new FormAttachment(wlFields, margin);
    fdFields.right = new FormAttachment(100, 0);
    fdFields.bottom = new FormAttachment(100, 0);
    wFields.setLayoutData(fdFields);

    sashForm.setWeights(new int[] {3, 2});

    loading = true;
    getData();
    loading = false;

    wFields.optWidth(true);
    wOk = new Button(shell, SWT.PUSH);
    wOk.setText(BaseMessages.getString(PKG, "System.Button.OK"));
    wCancel = new Button(shell, SWT.PUSH);
    wCancel.setText(BaseMessages.getString(PKG, "System.Button.Cancel"));
    setButtonPositions(new Button[] {wOk, wCancel}, margin, null);

    fdSash.bottom = new FormAttachment(wOk, -margin * 2);
    setSize(shell, 960, 720, true);

    wOk.addListener(SWT.Selection, e -> ok());
    wCancel.addListener(SWT.Selection, e -> cancel());
    wTransformName.setFocus();
    BaseDialog.defaultShellHandling(shell, c -> ok(), c -> cancel());

    return transformName;
  }

  private void getData() {
    wTransformName.setText(Const.NVL(transformName, ""));
    selectExecutionMode(input.getExecutionModeEnum());
    wScript.setText(Const.NVL(input.getScriptText(), ""));

    wFields.clearAll(false);
    for (GraalPyOutputField field : input.getOutputFields()) {
      wFields.add(
          Const.NVL(field.getName(), ""),
          Const.NVL(field.getType(), ""),
          intValue(field.getLength()),
          intValue(field.getPrecision()),
          field.isReplaceExisting() ? YES_NO_COMBO[1] : YES_NO_COMBO[0]);
    }

    input.setChanged(changed);
  }

  private void getInfo() {
    input.setScriptText(wScript.getText());
    input.setExecutionMode(getSelectedExecutionMode().getCode());

    List<GraalPyOutputField> outputFields = new ArrayList<>();
    for (TableItem item : wFields.getNonEmptyItems()) {
      GraalPyOutputField field = new GraalPyOutputField();
      field.setName(item.getText(1));
      field.setType(item.getText(2));
      field.setLength(parseInteger(item.getText(3)));
      field.setPrecision(parseInteger(item.getText(4)));
      field.setReplaceExisting(YES_NO_COMBO[1].equals(item.getText(5)));
      outputFields.add(field);
    }
    input.setOutputFields(outputFields);
  }

  private void selectExecutionMode(GraalPyExecutionMode executionMode) {
    if (executionMode == GraalPyExecutionMode.EMIT_MANY) {
      wMode.select(1);
    } else {
      wMode.select(0);
    }
  }

  private GraalPyExecutionMode getSelectedExecutionMode() {
    return wMode.getSelectionIndex() == 1
        ? GraalPyExecutionMode.EMIT_MANY
        : GraalPyExecutionMode.RETURN_ONE;
  }

  private String intValue(int value) {
    return value > 0 ? Integer.toString(value) : "";
  }

  private int parseInteger(String value) {
    if (Utils.isEmpty(value)) {
      return -1;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private void ok() {
    if (Utils.isEmpty(wTransformName.getText())) {
      return;
    }

    transformName = wTransformName.getText();
    getInfo();
    dispose();
  }

  private void cancel() {
    transformName = null;
    input.setChanged(changed);
    dispose();
  }
}
