package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.core.exception.*;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.*;

/** Hop row flow adapter; execution is shared with the preview through PythonRuntimeSession. */
public class GraalPyTransform extends BaseTransform<GraalPyTransformMeta, GraalPyTransformData>
    implements ITransform {
  public GraalPyTransform(
      TransformMeta transformMeta,
      GraalPyTransformMeta meta,
      GraalPyTransformData data,
      int copyNr,
      PipelineMeta pipelineMeta,
      Pipeline pipeline) {
    super(transformMeta, meta, data, copyNr, pipelineMeta, pipeline);
  }

  @Override
  public boolean init() {
    if (!super.init()) return false;
    data.session =
        new PythonRuntimeSession(
            meta,
            this::resolve,
            getTransformName(),
            getCopy(),
            (level, message) -> {
              if ("ERROR".equals(level)) logError(message);
              else logBasic(message);
            });
    try {
      data.session.open();
      return true;
    } catch (Exception e) {
      logError(e.getMessage(), e);
      try {
        data.session.close();
      } catch (Exception cleanup) {
        logError(cleanup.getMessage(), cleanup);
      }
      return false;
    }
  }

  @Override
  public boolean processRow() throws HopException {
    Object[] row = getRow();
    if (isStopped()) return false;
    if (first) {
      first = false;
      org.apache.hop.core.row.IRowMeta incoming = getInputRowMeta();
      if (incoming == null && getPipelineMeta() != null)
        incoming = getPipelineMeta().getPrevTransformFields(this, getTransformMeta());
      data.session.prepare(incoming);
    }
    if (row == null) {
      data.session.close();
      setOutputDone();
      return false;
    }
    PythonRuntimeSession.Result result = data.session.process(row, getLinesRead());
    if (result.rejection() != null) {
      PythonRuntimeSession.Rejection rejection = result.rejection();
      if (!getTransformMeta().isDoingErrorHandling())
        throw new HopTransformException(
            "Python rejected row without an error hop: "
                + rejection.code()
                + ": "
                + rejection.message());
      putError(getInputRowMeta(), row, 1, rejection.message(), rejection.field(), rejection.code());
    }
    for (Object[] output : result.rows()) {
      if (isStopped()) return false;
      putRow(data.session.outputMeta(), output);
    }
    return true;
  }

  @Override
  public void stopRunning() throws HopException {
    if (data.session != null) data.session.cancel();
    super.stopRunning();
  }

  @Override
  public void dispose() {
    try {
      if (data.session != null) data.session.close();
    } catch (Exception e) {
      logError(e.getMessage(), e);
    } finally {
      super.dispose();
    }
  }
}
