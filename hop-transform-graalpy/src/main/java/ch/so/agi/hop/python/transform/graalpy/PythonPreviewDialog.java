package ch.so.agi.hop.python.transform.graalpy;

import java.util.*;
import java.util.List;
import org.apache.hop.core.row.*;
import org.apache.hop.core.row.value.*;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.widget.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Session-local manual input. A separate null selector preserves empty strings. */
public final class PythonPreviewDialog {
  private final Shell parent;
  private final IVariables variables;
  private final GraalPyTransformMeta configuration;
  private final String name;
  private Shell shell;
  private TableView schema, values;
  private Composite inputArea;
  private Spinner count;
  private RowMeta metadata;
  private Text output, rejected, logs, errors;
  private Button run, stop, apply;
  private volatile PythonPreviewRunner runner;
  private final StringBuffer logBuffer = new StringBuffer();
  private boolean running;

  public PythonPreviewDialog(
      Shell parent, IVariables variables, GraalPyTransformMeta configuration, String name) {
    this.parent = parent;
    this.variables = variables;
    this.configuration = configuration.clone();
    this.name = name;
  }

  public void open() {
    shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX);
    shell.setText("GraalPy — Test script");
    shell.setLayout(new GridLayout(1, false));
    Label hint = new Label(shell, SWT.WRAP);
    hint.setText(
        "Define the input schema and apply it to edit sample rows. Null is separate from an empty"
            + " value.\n"
            + "Binary and Geometry values use hex WKB/EWKB. Maximum: 100 inputs, 1000 outputs, 30"
            + " seconds.");
    hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    schema =
        new TableView(
            variables,
            shell,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL,
            new ColumnInfo[] {
              GraalPyTransformDialog.column("Field name"),
              GraalPyTransformDialog.choice("Type", GraalPyOutputField.supportedTypeNames())
            },
            2,
            e -> {},
            PropsUi.getInstance());
    schema.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
    ((GridData) schema.getLayoutData()).heightHint = 100;
    Composite tools = new Composite(shell, SWT.NONE);
    tools.setLayout(new RowLayout());
    new Label(tools, SWT.NONE).setText("Input rows:");
    count = new Spinner(tools, SWT.BORDER);
    count.setMinimum(0);
    count.setMaximum(100);
    count.setSelection(1);
    apply = GraalPyTransformDialog.button(tools, "Apply schema / row count", this::applySchema);
    inputArea = new Composite(shell, SWT.NONE);
    inputArea.setLayout(new FillLayout());
    inputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    TabFolder results = new TabFolder(shell, SWT.NONE);
    results.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    output = resultTab(results, "Output");
    rejected = resultTab(results, "Rejected rows");
    logs = resultTab(results, "Logs");
    errors = resultTab(results, "Errors");
    Composite actions = new Composite(shell, SWT.NONE);
    actions.setLayout(new RowLayout());
    run = GraalPyTransformDialog.button(actions, "Run", this::start);
    stop =
        GraalPyTransformDialog.button(
            actions,
            "Stop",
            () -> {
              if (runner != null) runner.cancel();
            });
    stop.setEnabled(false);
    GraalPyTransformDialog.button(actions, "Close", shell::dispose);
    shell.addListener(
        SWT.Dispose,
        e -> {
          if (runner != null) runner.cancel();
        });
    shell.setSize(1000, 850);
    shell.open();
  }

  private Text resultTab(TabFolder folder, String name) {
    TabItem item = new TabItem(folder, SWT.NONE);
    item.setText(name);
    Text text =
        new Text(folder, SWT.MULTI | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
    item.setControl(text);
    return text;
  }

  private void applySchema() {
    try {
      RowMeta next = new RowMeta();
      Set<String> names = new HashSet<>();
      for (TableItem row : schema.getNonEmptyItems()) {
        if (row.getText(1).isBlank() || !names.add(row.getText(1)))
          throw new IllegalArgumentException("Input field names must be non-empty and unique");
        int type = ValueMetaFactory.getIdForValueMeta(row.getText(2));
        if (!GraalPyOutputField.isSupportedType(type))
          throw new IllegalArgumentException("Select a supported field type");
        next.addValueMeta(ValueMetaFactory.createValueMeta(row.getText(1), type));
      }
      if (values != null) values.dispose();
      List<ColumnInfo> columns = new ArrayList<>();
      for (IValueMeta field : next.getValueMetaList()) {
        columns.add(GraalPyTransformDialog.column(field.getName()));
        columns.add(
            GraalPyTransformDialog.choice(
                field.getName() + " is null", new String[] {"No", "Yes"}));
      }
      // A zero-field schema still supports explicit empty rows.
      if (columns.isEmpty()) columns.add(GraalPyTransformDialog.column("Empty row"));
      values =
          new TableView(
              variables,
              inputArea,
              SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL,
              columns.toArray(ColumnInfo[]::new),
              Math.max(1, count.getSelection()),
              e -> {},
              PropsUi.getInstance());
      metadata = next;
      inputArea.layout(true, true);
    } catch (Exception e) {
      errors.setText(e.toString());
    }
  }

  private void start() {
    if (running) return;
    try {
      if (metadata == null) applySchema();
      if (metadata == null) return;
      List<Object[]> rows = new ArrayList<>();
      for (int r = 0; r < count.getSelection(); r++) {
        Object[] row = new Object[metadata.size()];
        TableItem item = values.table.getItem(r);
        for (int c = 0; c < metadata.size(); c++) {
          if ("Yes".equals(item.getText(2 + 2 * c))) continue;
          String text = item.getText(1 + 2 * c);
          IValueMeta field = metadata.getValueMeta(c);
          if (field.getType() == IValueMeta.TYPE_BINARY) row[c] = HexFormat.of().parseHex(text);
          else if (field.getType() == PythonGeometryAdapter.TYPE)
            row[c] = new PythonGeometryAdapter(null).fromWkbBytes(HexFormat.of().parseHex(text));
          else row[c] = field.convertData(new ValueMetaString(), text);
        }
        rows.add(row);
      }
      logBuffer.setLength(0);
      output.setText("");
      rejected.setText("");
      errors.setText("");
      logs.setText("");
      runner =
          new PythonPreviewRunner(
              configuration,
              variables::resolve,
              name,
              (level, message) ->
                  logBuffer.append(level).append(": ").append(message).append('\n'));
      running = true;
      setEnabled(false);
      refreshLogs();
      Display display = shell.getDisplay();
      Thread worker =
          new Thread(
              () -> {
                String resultText = "", rejectedText = "", error = "";
                try {
                  PythonPreviewRunner.Preview result = runner.run(metadata, rows);
                  resultText = formatRows(result.metadata(), result.rows());
                  StringBuilder rejectedBuilder = new StringBuilder();
                  for (PythonPreviewRunner.Rejected row : result.rejected())
                    rejectedBuilder
                        .append(formatRows(metadata, Collections.singletonList(row.input())))
                        .append(row.error())
                        .append('\n');
                  rejectedText = rejectedBuilder.toString();
                } catch (Exception e) {
                  error = e.getMessage();
                }
                String finalResult = resultText, finalRejected = rejectedText, finalError = error;
                if (!display.isDisposed())
                  display.asyncExec(
                      () -> {
                        if (shell.isDisposed()) return;
                        output.setText(finalResult);
                        rejected.setText(finalRejected);
                        errors.setText(Objects.toString(finalError, ""));
                        logs.setText(logBuffer.toString());
                        running = false;
                        setEnabled(true);
                        runner = null;
                      });
              },
              "graalpy-preview");
      worker.setDaemon(true);
      worker.start();
    } catch (Exception e) {
      errors.setText(e.toString());
    }
  }

  private void setEnabled(boolean idle) {
    run.setEnabled(idle);
    stop.setEnabled(!idle);
    apply.setEnabled(idle);
    count.setEnabled(idle);
    schema.setEnabled(idle);
    if (values != null) values.setEnabled(idle);
  }

  private void refreshLogs() {
    if (shell.isDisposed() || !running) return;
    logs.setText(logBuffer.toString());
    shell.getDisplay().timerExec(200, this::refreshLogs);
  }

  static String formatRows(IRowMeta metadata, List<Object[]> rows) throws Exception {
    StringBuilder result =
        new StringBuilder(String.join("\t", metadata.getFieldNames())).append('\n');
    PythonGeometryAdapter geometry = new PythonGeometryAdapter(null);
    for (Object[] row : rows) {
      for (int i = 0; i < metadata.size(); i++) {
        if (i > 0) result.append('\t');
        Object value = row[i];
        String text;
        if (value == null) text = "<NULL>";
        else if (value instanceof byte[] bytes) text = HexFormat.of().formatHex(bytes);
        else if (metadata.getValueMeta(i).getType() == PythonGeometryAdapter.TYPE)
          text =
              geometry.toWktText(value)
                  + " [EWKB="
                  + HexFormat.of().formatHex(geometry.toWkbBytes(value))
                  + "]";
        else text = metadata.getValueMeta(i).getString(value);
        result.append(text.replace("\t", "\\t").replace("\n", "\\n"));
      }
      result.append('\n');
    }
    return result.toString();
  }
}
