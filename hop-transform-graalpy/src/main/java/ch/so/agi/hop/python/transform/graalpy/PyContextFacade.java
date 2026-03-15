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
    if (executionMode != GraalPyExecutionMode.EMIT_MANY) {
      throw new HopTransformException("ctx.emit(...) is only allowed in EMIT_MANY mode.");
    }
    Map<String, Object> snapshot = typeBridge.snapshotMapping(mapping, outputRowAssembler);
    emittedRows.add(outputRowAssembler.createOutputRow(currentInputRow, snapshot));
  }

  @Export
  public void skip() {
    throw new SkipCurrentRowException();
  }

  @Export
  public void abort(String message) {
    throw new AbortTransformException(message);
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
