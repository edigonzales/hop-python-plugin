package ch.so.agi.hop.python.transform.graalpy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.exception.HopTransformException;
import org.graalvm.polyglot.HostAccess.Export;
import org.graalvm.polyglot.Value;

public final class PyContextFacade {
  private final GraalPyExecutionMode executionMode;
  private final OutputRowAssembler outputRowAssembler;
  private final HopPythonTypeBridge typeBridge;
  private final PyLoggerFacade log;
  private final List<Map<String, Object>> fields;
  private final List<Object[]> emittedRows;

  private Object[] currentInputRow;
  private boolean processingRow;
  private long maxEmittedRows;
  private RuntimeException signal;

  void checkSignal() {
    if (signal != null) throw signal;
  }

  private void signal(RuntimeException exception) {
    if (signal == null) signal = exception;
    throw signal;
  }

  public void setMaxEmittedRows(long limit) {
    maxEmittedRows = limit;
  }

  public void endRow() {
    processingRow = false;
    currentInputRow = null;
    emittedRows.clear();
    signal = null;
  }

  public PyContextFacade(
      GraalPyExecutionMode executionMode,
      OutputRowAssembler outputRowAssembler,
      HopPythonTypeBridge typeBridge,
      PyLoggerFacade log,
      List<Map<String, Object>> fields) {
    this.executionMode = executionMode;
    this.outputRowAssembler = outputRowAssembler;
    this.typeBridge = typeBridge;
    this.log = log;
    this.fields = fields;
    this.emittedRows = new ArrayList<>();
  }

  public void beginRow(Object[] inputRow) {
    this.currentInputRow = inputRow;
    processingRow = true;
    signal = null;
    this.emittedRows.clear();
  }

  public List<Object[]> drainEmittedRows() {
    List<Object[]> rows = new ArrayList<>(emittedRows);
    emittedRows.clear();
    return rows;
  }

  public void clearEmittedRows() {
    emittedRows.clear();
  }

  @Export
  public void emit(Value mapping) throws HopTransformException {
    checkSignal();
    if (!processingRow) throw new HopTransformException("emit is only allowed during process");
    if (maxEmittedRows > 0 && emittedRows.size() >= maxEmittedRows)
      signal(new IllegalStateException("Python output limit exceeded"));
    if (executionMode != GraalPyExecutionMode.EMIT_MANY) {
      throw new HopTransformException("ctx.emit(...) is only allowed in EMIT_MANY mode.");
    }
    Map<String, Object> snapshot = typeBridge.snapshotMapping(mapping, outputRowAssembler);
    emittedRows.add(outputRowAssembler.createOutputRow(currentInputRow, snapshot));
  }

  @Export
  public void reject(String code, String message, String field) {
    if (!processingRow) throw new IllegalStateException("reject is only allowed during process");
    if (code == null || code.isBlank() || message == null || message.isBlank())
      throw new IllegalArgumentException("reject requires a code and message");
    signal(new RejectCurrentRowException(code, message, field));
  }

  @Export
  public void skip() {
    if (!processingRow) throw new IllegalStateException("skip is only allowed during process");
    signal(new SkipCurrentRowException());
  }

  @Export
  public void abort(String message) {
    signal(new AbortTransformException(message));
  }

  @Export
  public PyLoggerFacade getLog() {
    return log;
  }

  @Export
  public List<Map<String, Object>> getFields() {
    return Collections.unmodifiableList(fields);
  }
}

final class SkipCurrentRowException extends RuntimeException {
  SkipCurrentRowException() {
    super("skip", null, false, false);
  }
}

final class AbortTransformException extends RuntimeException {
  AbortTransformException(String message) {
    super(message, null, false, false);
  }
}

final class RejectCurrentRowException extends RuntimeException {
  final String code;
  final String field;

  RejectCurrentRowException(String code, String message, String field) {
    super(message, null, false, false);
    this.code = code;
    this.field = field;
  }
}
