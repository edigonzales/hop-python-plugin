package ch.so.agi.hop.python.transform.graalpy;

import java.nio.file.*;
import java.util.*;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.ui.core.dialog.BaseDialog;
import org.apache.hop.ui.core.dialog.ErrorDialog;
import org.apache.hop.ui.core.widget.*;
import org.apache.hop.ui.pipeline.transform.BaseTransformDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

public class GraalPyTransformDialog extends BaseTransformDialog {
  private final GraalPyTransformMeta input;
  private Text name;
  private Combo mode, source, selection;
  private TextVar scriptPath, venv;
  private Text timeout, outputLimit, logLimit;
  private Button external, nativeAccess;
  private ScriptStyledTextComp script;
  private TableView fields, inputs, parameters;
  private Path editorFile;
  private Button save;

  public GraalPyTransformDialog(
      Shell parent, IVariables variables, GraalPyTransformMeta meta, PipelineMeta pipeline) {
    super(parent, variables, meta, pipeline);
    input = meta;
  }

  public GraalPyTransformDialog(
      Shell parent, IVariables variables, Object meta, PipelineMeta pipeline, String name) {
    this(parent, variables, (ITransformMeta) meta, pipeline, name);
  }

  public GraalPyTransformDialog(
      Shell parent, IVariables variables, ITransformMeta meta, PipelineMeta pipeline, String name) {
    super(parent, variables, meta, pipeline, name);
    input = (GraalPyTransformMeta) meta;
  }

  @Override
  public String open() {
    shell = new Shell(getParent(), SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.MIN);
    shell.setText("GraalPy");
    setShellImage(shell, input);
    shell.setLayout(new GridLayout(2, false));
    label(shell, "Transform name");
    name = text(shell, Objects.toString(transformName, ""));
    label(shell, "Execution mode");
    mode = combo(shell, new String[] {"RETURN_ONE", "EMIT_MANY"}, input.getExecutionMode());
    TabFolder tabs = new TabFolder(shell, SWT.NONE);
    tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    Composite scriptPage = page(tabs, "Script", 2);
    label(scriptPage, "Source");
    source = combo(scriptPage, new String[] {"INLINE", "FILE"}, input.getScriptSource());
    label(scriptPage, "Runtime script file");
    Composite fileLine = new Composite(scriptPage, SWT.NONE);
    fileLine.setLayout(new GridLayout(2, false));
    fill(fileLine);
    scriptPath = new TextVar(variables, fileLine, SWT.BORDER);
    fill(scriptPath);
    scriptPath.setText(input.getScriptPath());
    button(
        fileLine,
        "Browse…",
        () -> {
          String path = chooseFile(SWT.OPEN);
          if (path != null) scriptPath.setText(path);
        });
    Label hint =
        label(
            scriptPage,
            "FILE is loaded once per run. Relative paths use ${PROJECT_HOME}.\n"
                + "The editor below is the persisted INLINE script; Load/Save only import or export"
                + " editor contents.");
    hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Composite fileButtons = new Composite(scriptPage, SWT.NONE);
    fileButtons.setLayout(new RowLayout());
    fileButtons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    button(fileButtons, "Load…", this::loadFile);
    save = button(fileButtons, "Save", () -> saveFile(false));
    save.setEnabled(false);
    button(fileButtons, "Save as…", () -> saveFile(true));
    script =
        new ScriptStyledTextComp(
            variables, scriptPage, SWT.MULTI | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL, false);
    script.addLineStyleListener(new PythonCodeHighlight());
    script.setText(input.getScriptText());
    script.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    source.addListener(SWT.Selection, e -> updateSource());
    updateSource();

    Composite inputPage = page(tabs, "Inputs / Parameters", 2);
    label(inputPage, "Fields visible to Python");
    selection = combo(inputPage, new String[] {"ALL", "SELECTED"}, input.getInputMode());
    inputs = table(inputPage, new ColumnInfo[] {column("Input field")}, 4);
    inputs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    input.getSelectedInputs().forEach(f -> inputs.add(f.getName()));
    selection.addListener(
        SWT.Selection, e -> inputs.setEnabled("SELECTED".equals(selection.getText())));
    inputs.setEnabled("SELECTED".equals(selection.getText()));
    Label pHint =
        label(inputPage, "Parameters are strings; Hop variables are resolved once at startup.");
    pHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    parameters = table(inputPage, new ColumnInfo[] {column("Name"), column("Value")}, 4);
    parameters.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    input
        .getParameters()
        .forEach(p -> parameters.add(p.getName(), Objects.toString(p.getValue(), "")));

    Composite outputPage = page(tabs, "Output fields", 1);
    label(
        outputPage,
        "Declare only new or changed fields. return {} passes a row through; return None filters"
            + " it.");
    fields =
        table(
            outputPage,
            new ColumnInfo[] {
              column("Name"),
              choice("Type", GraalPyOutputField.supportedTypeNames()),
              column("Length"),
              column("Precision"),
              choice("Replace existing", new String[] {"No", "Yes"})
            },
            5);
    fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    input
        .getOutputFields()
        .forEach(
            f ->
                fields.add(
                    f.getName(),
                    f.getType(),
                    number(f.getLength()),
                    number(f.getPrecision()),
                    f.isReplaceExisting() ? "Yes" : "No"));

    Composite environment = page(tabs, "Environment / Limits", 2);
    label(environment, "External GraalPy environment");
    external = check(environment, input.isExternalEnvironmentEnabled());
    label(environment, "GraalPy venv path");
    Composite venvLine = new Composite(environment, SWT.NONE);
    venvLine.setLayout(new GridLayout(2, false));
    fill(venvLine);
    venv = new TextVar(variables, venvLine, SWT.BORDER);
    venv.setText(input.getGraalPyVenvPath());
    fill(venv);
    button(
        venvLine,
        "Browse…",
        () -> {
          String path = new DirectoryDialog(shell).open();
          if (path != null) venv.setText(path);
        });
    label(environment, "Native access (experimental)");
    nativeAccess = check(environment, input.isNativeAccessEnabled());
    Label warning =
        label(
            environment,
            "External environments have filesystem/network I/O access. Native code is trusted and"
                + " unrestricted.\n"
                + "Use a GraalPy "
                + GraalPyExternalEnvironment.EXPECTED_VERSION
                + " venv; install packages outside Hop.");
    warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    label(environment, "Seconds per phase / input (0 = unlimited)");
    timeout = text(environment, "" + input.getExecutionTimeoutSeconds());
    label(environment, "Emitted rows per input (0 = unlimited)");
    outputLimit = text(environment, "" + input.getMaxEmittedRows());
    label(environment, "Log bytes per session (0 = unlimited)");
    logLimit = text(environment, "" + input.getMaxLogBytes());
    external.addListener(
        SWT.Selection,
        e -> {
          venv.setEnabled(external.getSelection());
          nativeAccess.setEnabled(external.getSelection());
        });
    venv.setEnabled(external.getSelection());
    nativeAccess.setEnabled(external.getSelection());

    Composite actions = new Composite(shell, SWT.NONE);
    actions.setLayout(new RowLayout());
    actions.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false, 2, 1));
    button(
        actions,
        "Test…",
        () -> {
          try {
            new PythonPreviewDialog(shell, variables, collect(), name.getText()).open();
          } catch (Exception e) {
            error(e);
          }
        });
    button(actions, "OK", this::ok);
    button(actions, "Cancel", this::cancel);
    shell.setSize(1000, 780);
    BaseDialog.defaultShellHandling(shell, c -> ok(), c -> cancel());
    return transformName;
  }

  private GraalPyTransformMeta collect() {
    GraalPyTransformMeta result = input.clone();
    result.setScriptText(script.getText());
    result.setExecutionMode(mode.getText());
    result.setScriptSource(source.getText());
    result.setScriptPath(scriptPath.getText());
    result.setInputMode(selection.getText());
    result.setSelectedInputs(
        inputs.getNonEmptyItems().stream().map(i -> new GraalPyInputField(i.getText(1))).toList());
    result.setParameters(
        parameters.getNonEmptyItems().stream()
            .map(i -> new GraalPyParameter(i.getText(1), i.getText(2)))
            .toList());
    result.setOutputFields(
        fields.getNonEmptyItems().stream()
            .map(
                i -> {
                  GraalPyOutputField f = new GraalPyOutputField();
                  f.setName(i.getText(1));
                  f.setType(i.getText(2));
                  f.setLength(parse(i.getText(3)));
                  f.setPrecision(parse(i.getText(4)));
                  f.setReplaceExisting("Yes".equals(i.getText(5)));
                  return f;
                })
            .toList());
    result.setExternalEnvironmentEnabled(external.getSelection());
    result.setGraalPyVenvPath(venv.getText());
    result.setNativeAccessEnabled(nativeAccess.getSelection());
    result.setExecutionTimeoutSeconds(Long.parseLong(timeout.getText()));
    result.setMaxEmittedRows(Long.parseLong(outputLimit.getText()));
    result.setMaxLogBytes(Long.parseLong(logLimit.getText()));
    return result;
  }

  private void ok() {
    if (name.getText().isBlank()) return;
    try {
      GraalPyTransformMeta result = collect();
      result.validate(null);
      input.copyConfigurationFrom(result);
      input.setChanged();
      transformName = name.getText();
      dispose();
    } catch (Exception e) {
      error(e);
    }
  }

  private void cancel() {
    transformName = null;
    dispose();
  }

  private void updateSource() {
    scriptPath.setEnabled("FILE".equals(source.getText()));
  }

  private void error(Exception e) {
    new ErrorDialog(shell, "GraalPy", "Unable to apply configuration", e);
  }

  private String chooseFile(int flags) {
    FileDialog dialog = new FileDialog(shell, flags);
    dialog.setFilterExtensions(new String[] {"*.py", "*.*"});
    return dialog.open();
  }

  private boolean confirm(String message) {
    MessageBox box = new MessageBox(shell, SWT.YES | SWT.NO | SWT.ICON_QUESTION);
    box.setText("GraalPy");
    box.setMessage(message);
    return box.open() == SWT.YES;
  }

  private void loadFile() {
    String path = chooseFile(SWT.OPEN);
    if (path == null || (!script.getText().isBlank() && !confirm("Replace editor contents?")))
      return;
    try {
      editorFile = Path.of(path);
      script.setText(GraalPyScriptFileSupport.load(editorFile));
      save.setEnabled(true);
    } catch (Exception e) {
      error(e);
    }
  }

  private void saveFile(boolean saveAs) {
    try {
      Path path = editorFile;
      if (saveAs || path == null) {
        String chosen = chooseFile(SWT.SAVE);
        if (chosen == null) return;
        path = Path.of(chosen);
        if (Files.exists(path) && !confirm("Overwrite " + path + "?")) return;
      }
      GraalPyScriptFileSupport.save(path, script.getText());
      editorFile = path;
      save.setEnabled(true);
    } catch (Exception e) {
      error(e);
    }
  }

  private TableView table(Composite parent, ColumnInfo[] columns, int rows) {
    TableView table =
        new TableView(
            variables,
            parent,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL,
            columns,
            rows,
            e -> {},
            props);
    table.clearAll(false);
    return table;
  }

  static ColumnInfo column(String name) {
    return new ColumnInfo(name, ColumnInfo.COLUMN_TYPE_TEXT);
  }

  static ColumnInfo choice(String name, String[] values) {
    return new ColumnInfo(name, ColumnInfo.COLUMN_TYPE_CCOMBO, values);
  }

  static void fill(Control control) {
    control.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
  }

  static Label label(Composite parent, String text) {
    Label label = new Label(parent, SWT.WRAP);
    label.setText(text);
    return label;
  }

  static Text text(Composite parent, String value) {
    Text text = new Text(parent, SWT.BORDER);
    text.setText(value);
    fill(text);
    return text;
  }

  static Button button(Composite parent, String text, Runnable action) {
    Button b = new Button(parent, SWT.PUSH);
    b.setText(text);
    b.addListener(SWT.Selection, e -> action.run());
    return b;
  }

  private Button check(Composite parent, boolean value) {
    Button b = new Button(parent, SWT.CHECK);
    b.setSelection(value);
    return b;
  }

  private Combo combo(Composite parent, String[] values, String selected) {
    Combo c = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
    c.setItems(values);
    c.setText(selected);
    fill(c);
    return c;
  }

  private Composite page(TabFolder folder, String title, int columns) {
    TabItem tab = new TabItem(folder, SWT.NONE);
    tab.setText(title);
    Composite page = new Composite(folder, SWT.NONE);
    page.setLayout(new GridLayout(columns, false));
    tab.setControl(page);
    return page;
  }

  private static int parse(String value) {
    return value.isBlank() ? -1 : Integer.parseInt(value);
  }

  private static String number(int value) {
    return value < 0 ? "" : Integer.toString(value);
  }
}
