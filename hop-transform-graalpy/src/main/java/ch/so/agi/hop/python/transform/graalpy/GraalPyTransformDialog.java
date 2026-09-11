package ch.so.agi.hop.python.transform.graalpy;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.Const;
import org.apache.hop.core.Props;
import org.apache.hop.core.util.Utils;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.dialog.BaseDialog;
import org.apache.hop.ui.core.dialog.ErrorDialog;
import org.apache.hop.ui.core.widget.ColumnInfo;
import org.apache.hop.ui.core.widget.ScriptStyledTextComp;
import org.apache.hop.ui.core.widget.TableView;
import org.apache.hop.ui.core.widget.TextVar;
import org.apache.hop.ui.pipeline.transform.BaseTransformDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
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
  private Button wExternalEnvironmentEnabled;
  private TextVar wGraalPyVenvPath;
  private Button wBrowseGraalPyVenvPath;
  private Label wlExternalEnvironmentWarning;
  private ScriptStyledTextComp wScript;
  private Button wLoadScript;
  private Button wSaveScript;
  private Button wSaveAsScript;
  private TableView wFields;
  private ModifyListener lsMod;
  private int middle;
  private int margin;
  private boolean loading;
  private Path currentScriptFile;

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

    Label wlExternalEnvironment = new Label(shell, SWT.RIGHT);
    wlExternalEnvironment.setText(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.ExternalEnvironment.Label"));
    wlExternalEnvironment.setToolTipText(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.ExternalEnvironment.Tooltip"));
    PropsUi.setLook(wlExternalEnvironment);
    FormData fdlExternalEnvironment = new FormData();
    fdlExternalEnvironment.left = new FormAttachment(0, 0);
    fdlExternalEnvironment.right = new FormAttachment(middle, -margin);
    fdlExternalEnvironment.top = new FormAttachment(wMode, margin);
    wlExternalEnvironment.setLayoutData(fdlExternalEnvironment);

    wExternalEnvironmentEnabled = new Button(shell, SWT.CHECK);
    wExternalEnvironmentEnabled.setText(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.ExternalEnvironment.Enable"));
    wExternalEnvironmentEnabled.setToolTipText(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.ExternalEnvironment.Tooltip"));
    PropsUi.setLook(wExternalEnvironmentEnabled);
    wExternalEnvironmentEnabled.addListener(
        SWT.Selection,
        e -> {
          if (!loading) {
            input.setChanged();
          }
          updateExternalEnvironmentControls();
        });
    FormData fdExternalEnvironment = new FormData();
    fdExternalEnvironment.left = new FormAttachment(middle, 0);
    fdExternalEnvironment.top = new FormAttachment(wMode, margin);
    fdExternalEnvironment.right = new FormAttachment(100, 0);
    wExternalEnvironmentEnabled.setLayoutData(fdExternalEnvironment);

    Label wlGraalPyVenvPath = new Label(shell, SWT.RIGHT);
    wlGraalPyVenvPath.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.VenvPath.Label"));
    wlGraalPyVenvPath.setToolTipText(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.VenvPath.Tooltip"));
    PropsUi.setLook(wlGraalPyVenvPath);
    FormData fdlGraalPyVenvPath = new FormData();
    fdlGraalPyVenvPath.left = new FormAttachment(0, 0);
    fdlGraalPyVenvPath.right = new FormAttachment(middle, -margin);
    fdlGraalPyVenvPath.top = new FormAttachment(wExternalEnvironmentEnabled, margin);
    wlGraalPyVenvPath.setLayoutData(fdlGraalPyVenvPath);

    wBrowseGraalPyVenvPath = new Button(shell, SWT.PUSH);
    wBrowseGraalPyVenvPath.setText(BaseMessages.getString(PKG, "System.Button.Browse"));
    PropsUi.setLook(wBrowseGraalPyVenvPath);
    FormData fdBrowseGraalPyVenvPath = new FormData();
    fdBrowseGraalPyVenvPath.right = new FormAttachment(100, 0);
    fdBrowseGraalPyVenvPath.top = new FormAttachment(wExternalEnvironmentEnabled, margin);
    wBrowseGraalPyVenvPath.setLayoutData(fdBrowseGraalPyVenvPath);

    wGraalPyVenvPath = new TextVar(variables, shell, SWT.SINGLE | SWT.LEFT | SWT.BORDER);
    wGraalPyVenvPath.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.VenvPath.Tooltip"));
    PropsUi.setLook(wGraalPyVenvPath);
    wGraalPyVenvPath.addModifyListener(lsMod);
    FormData fdGraalPyVenvPath = new FormData();
    fdGraalPyVenvPath.left = new FormAttachment(middle, 0);
    fdGraalPyVenvPath.top = new FormAttachment(wExternalEnvironmentEnabled, margin);
    fdGraalPyVenvPath.right = new FormAttachment(wBrowseGraalPyVenvPath, -margin);
    wGraalPyVenvPath.setLayoutData(fdGraalPyVenvPath);

    wlExternalEnvironmentWarning = new Label(shell, SWT.WRAP);
    wlExternalEnvironmentWarning.setText(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.ExternalEnvironment.Warning"));
    PropsUi.setLook(wlExternalEnvironmentWarning);
    FormData fdlExternalEnvironmentWarning = new FormData();
    fdlExternalEnvironmentWarning.left = new FormAttachment(middle, 0);
    fdlExternalEnvironmentWarning.top = new FormAttachment(wGraalPyVenvPath, margin);
    fdlExternalEnvironmentWarning.right = new FormAttachment(100, 0);
    wlExternalEnvironmentWarning.setLayoutData(fdlExternalEnvironmentWarning);

    Label wlHelp = new Label(shell, SWT.WRAP);
    wlHelp.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Help.Label"));
    PropsUi.setLook(wlHelp);
    FormData fdlHelp = new FormData();
    fdlHelp.left = new FormAttachment(0, 0);
    fdlHelp.top = new FormAttachment(wlExternalEnvironmentWarning, margin);
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

    Composite wScriptHeader = new Composite(wScriptComp, SWT.NONE);
    PropsUi.setLook(wScriptHeader);
    FormLayout scriptHeaderLayout = new FormLayout();
    scriptHeaderLayout.marginWidth = 0;
    scriptHeaderLayout.marginHeight = 0;
    wScriptHeader.setLayout(scriptHeaderLayout);
    FormData fdScriptHeader = new FormData();
    fdScriptHeader.left = new FormAttachment(0, 0);
    fdScriptHeader.top = new FormAttachment(0, 0);
    fdScriptHeader.right = new FormAttachment(100, 0);
    wScriptHeader.setLayoutData(fdScriptHeader);

    Composite wScriptButtons = new Composite(wScriptHeader, SWT.NONE);
    PropsUi.setLook(wScriptButtons);
    RowLayout scriptButtonLayout = new RowLayout();
    scriptButtonLayout.marginBottom = 0;
    scriptButtonLayout.marginLeft = 0;
    scriptButtonLayout.marginRight = 0;
    scriptButtonLayout.marginTop = 0;
    scriptButtonLayout.spacing = margin;
    wScriptButtons.setLayout(scriptButtonLayout);
    FormData fdScriptButtons = new FormData();
    fdScriptButtons.right = new FormAttachment(100, 0);
    fdScriptButtons.top = new FormAttachment(0, 0);
    wScriptButtons.setLayoutData(fdScriptButtons);

    wLoadScript = new Button(wScriptButtons, SWT.PUSH);
    wLoadScript.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Load"));
    PropsUi.setLook(wLoadScript);

    wSaveScript = new Button(wScriptButtons, SWT.PUSH);
    wSaveScript.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Save"));
    PropsUi.setLook(wSaveScript);

    wSaveAsScript = new Button(wScriptButtons, SWT.PUSH);
    wSaveAsScript.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.SaveAs"));
    PropsUi.setLook(wSaveAsScript);

    Label wlScript = new Label(wScriptHeader, SWT.NONE);
    wlScript.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Label"));
    wlScript.setToolTipText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Tooltip"));
    PropsUi.setLook(wlScript);
    FormData fdlScript = new FormData();
    fdlScript.left = new FormAttachment(0, 0);
    fdlScript.top = new FormAttachment(0, 0);
    fdlScript.right = new FormAttachment(wScriptButtons, -margin);
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
    fdScript.top = new FormAttachment(wScriptHeader, margin);
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
    updateExternalEnvironmentControls();
    updateScriptFileButtons();

    wFields.optWidth(true);
    wOk = new Button(shell, SWT.PUSH);
    wOk.setText(BaseMessages.getString(PKG, "System.Button.OK"));
    wCancel = new Button(shell, SWT.PUSH);
    wCancel.setText(BaseMessages.getString(PKG, "System.Button.Cancel"));
    setButtonPositions(new Button[] {wOk, wCancel}, margin, null);

    fdSash.bottom = new FormAttachment(wOk, -margin * 2);
    setSize(shell, 960, 720, true);

    wLoadScript.addListener(SWT.Selection, e -> loadScriptFromFile());
    wSaveScript.addListener(SWT.Selection, e -> saveScript(false));
    wSaveAsScript.addListener(SWT.Selection, e -> saveScript(true));
    wBrowseGraalPyVenvPath.addListener(SWT.Selection, e -> browseGraalPyVenvPath());
    wOk.addListener(SWT.Selection, e -> ok());
    wCancel.addListener(SWT.Selection, e -> cancel());
    wTransformName.setFocus();
    BaseDialog.defaultShellHandling(shell, c -> ok(), c -> cancel());

    return transformName;
  }

  private void getData() {
    wTransformName.setText(Const.NVL(transformName, ""));
    selectExecutionMode(input.getExecutionModeEnum());
    wExternalEnvironmentEnabled.setSelection(input.isExternalEnvironmentEnabled());
    wGraalPyVenvPath.setText(Const.NVL(input.getGraalPyVenvPath(), ""));
    wScript.setText(Const.NVL(input.getScriptText(), ""));
    currentScriptFile = null;

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
    input.setExternalEnvironmentEnabled(wExternalEnvironmentEnabled.getSelection());
    input.setGraalPyVenvPath(wGraalPyVenvPath.getText());

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

  private void updateExternalEnvironmentControls() {
    boolean enabled = wExternalEnvironmentEnabled.getSelection();
    wGraalPyVenvPath.setEnabled(enabled);
    wBrowseGraalPyVenvPath.setEnabled(enabled);
    wlExternalEnvironmentWarning.setEnabled(enabled);
  }

  private void updateScriptFileButtons() {
    wSaveScript.setEnabled(currentScriptFile != null);
  }

  private void browseGraalPyVenvPath() {
    DirectoryDialog dialog = new DirectoryDialog(shell);
    String current = wGraalPyVenvPath.getText();
    if (!Utils.isEmpty(current)) {
      dialog.setFilterPath(current);
    }
    String selected = dialog.open();
    if (selected != null) {
      wGraalPyVenvPath.setText(selected);
    }
  }

  private void loadScriptFromFile() {
    FileDialog dialog = new FileDialog(shell, SWT.OPEN);
    dialog.setFilterExtensions(new String[] {"*.py", "*.*"});
    applyCurrentScriptFile(dialog);
    String selected = dialog.open();
    if (selected == null) {
      return;
    }
    if (!Utils.isEmpty(wScript.getText()) && !confirmReplaceScript()) {
      return;
    }
    try {
      Path path = Path.of(selected);
      wScript.setText(GraalPyScriptFileSupport.load(path));
      currentScriptFile = path;
      updateScriptFileButtons();
    } catch (Exception e) {
      new ErrorDialog(
          shell,
          BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.LoadError.Title"),
          BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.LoadError.Message"),
          e);
    }
  }

  private boolean confirmReplaceScript() {
    MessageBox confirmation = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
    confirmation.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Replace.Title"));
    confirmation.setMessage(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Replace.Message"));
    return confirmation.open() == SWT.YES;
  }

  private void saveScript(boolean forceSaveAs) {
    Path target = currentScriptFile;
    if (forceSaveAs || target == null) {
      FileDialog dialog = new FileDialog(shell, SWT.SAVE);
      dialog.setFilterExtensions(new String[] {"*.py", "*.*"});
      applyCurrentScriptFile(dialog);
      String selected = dialog.open();
      if (selected == null) {
        return;
      }
      target = Path.of(selected);
    }
    if (forceSaveAs && Files.exists(target) && !confirmOverwrite(target)) {
      return;
    }
    try {
      GraalPyScriptFileSupport.save(target, wScript.getText());
      currentScriptFile = target;
      updateScriptFileButtons();
    } catch (Exception e) {
      new ErrorDialog(
          shell,
          BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.SaveError.Title"),
          BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.SaveError.Message"),
          e);
    }
  }

  private boolean confirmOverwrite(Path target) {
    MessageBox confirmation = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
    confirmation.setText(BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Overwrite.Title"));
    confirmation.setMessage(
        BaseMessages.getString(PKG, "GraalPyTransformDialog.Script.Overwrite.Message", target));
    return confirmation.open() == SWT.YES;
  }

  private void applyCurrentScriptFile(FileDialog dialog) {
    if (currentScriptFile == null) {
      return;
    }
    Path parent = currentScriptFile.getParent();
    if (parent != null) {
      dialog.setFilterPath(parent.toString());
    }
    dialog.setFileName(currentScriptFile.getFileName().toString());
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
