package ch.so.agi.hop.python.transform.graalpy;

import java.util.List;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.BaseTransform;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

public class GraalPyTransform extends BaseTransform<GraalPyTransformMeta, GraalPyTransformData>
    implements ITransform {
  private static final Class<?> PKG = GraalPyTransform.class;

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
    if (!super.init()) {
      return false;
    }

    try {
      meta.validate(null);
      data.context = withPluginClassLoader(this::createContext);
      data.typeBridge = new HopPythonTypeBridge(data.context);
      loadScript();
      logDetailed(
          BaseMessages.getString(
              PKG,
              "GraalPyTransform.Log.ScriptLoaded",
              getTransformName(),
              meta.getExecutionModeEnum().getCode()));
      return true;
    } catch (Exception e) {
      logError(e.getMessage(), e);
      return false;
    }
  }

  @Override
  public boolean processRow() throws HopException {
    Object[] row = getRow();
    if (row == null) {
      setOutputDone();
      return false;
    }

    if (first) {
      first = false;
      initializeInputState(getInputRowMeta());
    }

    long rowNumber = getLinesRead();
    data.pyContext.beginRow(row);
    try {
      Value pythonRow = data.typeBridge.createPythonRow(data.inputRowMeta, row);
      Value result = withPluginClassLoader(() -> data.processFunction.execute(pythonRow, data.pyContext));
      handleResult(row, result);
    } catch (PolyglotException e) {
      if (e.isHostException()) {
        Throwable host = e.asHostException();
        if (host instanceof SkipCurrentRowException) {
          data.pyContext.clearEmittedRows();
          return true;
        }
        if (host instanceof AbortTransformException abort) {
          throw new HopTransformException(
              BaseMessages.getString(PKG, "GraalPyTransform.Exception.AbortSignal", abort.getMessage()));
        }
      }

      logError(BaseMessages.getString(PKG, "GraalPyTransform.Log.RowFailed", rowNumber, e.getMessage()), e);
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.RowFailed", rowNumber, e.getMessage()), e);
    }

    return true;
  }

  @Override
  public void dispose() {
    if (data.context != null) {
      try {
        withPluginClassLoader(
            () -> {
              data.context.close();
              return null;
            });
      } catch (HopException e) {
        logError(e.getMessage(), e);
      } finally {
        data.context = null;
      }
    }
    super.dispose();
  }

  private void initializeInputState(IRowMeta inputRowMeta) throws HopTransformException {
    data.inputRowMeta = inputRowMeta == null ? null : inputRowMeta.clone();
    meta.validate(data.inputRowMeta);
    data.typeBridge.validateInputTypes(data.inputRowMeta);

    data.outputRowMeta = data.inputRowMeta == null ? null : data.inputRowMeta.clone();
    if (data.outputRowMeta == null) {
      data.outputRowMeta = new org.apache.hop.core.row.RowMeta();
    }
    meta.getFields(data.outputRowMeta, getTransformName(), null, null, this, metadataProvider);
    data.outputRowAssembler =
        new OutputRowAssembler(data.inputRowMeta, data.outputRowMeta, meta.getOutputFields());
    data.pyContext =
        new PyContextFacade(
            meta.getExecutionModeEnum(),
            data.outputRowAssembler,
            data.typeBridge,
            new PyLoggerFacade(getLogChannel()),
            data.outputRowAssembler.buildFieldMetadata(data.inputRowMeta));
  }

  private void handleResult(Object[] inputRow, Value result) throws HopException {
    if (meta.getExecutionModeEnum() == GraalPyExecutionMode.RETURN_ONE) {
      if (result == null || result.isNull()) {
        return;
      }
      if (!result.hasHashEntries()) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG,
                "GraalPyTransform.Exception.ModeReturnExpected",
                data.typeBridge.describeValue(result)));
      }
      putRow(
          data.outputRowMeta,
          data.outputRowAssembler.createOutputRow(
              inputRow, data.typeBridge.snapshotMapping(result, data.outputRowAssembler)));
      return;
    }

    if (result != null && !result.isNull()) {
      throw new HopTransformException(
          BaseMessages.getString(
              PKG,
              "GraalPyTransform.Exception.ModeEmitExpected",
              data.typeBridge.describeValue(result)));
    }

    List<Object[]> emittedRows = data.pyContext.drainEmittedRows();
    for (Object[] emittedRow : emittedRows) {
      putRow(data.outputRowMeta, emittedRow);
    }
  }

  private void loadScript() throws HopException {
    if (org.apache.commons.lang.StringUtils.isBlank(meta.getScriptText())) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.EmptyScript"));
    }

    withPluginClassLoader(
        () -> {
          Source scriptSource =
              Source.newBuilder("python", meta.getScriptText(), getTransformName() + ".py")
                  .buildLiteral();
          data.context.eval(scriptSource);
          data.processFunction = data.context.getBindings("python").getMember("process");
          return null;
        });

    if (data.processFunction == null || !data.processFunction.canExecute()) {
      throw new HopTransformException(
          BaseMessages.getString(PKG, "GraalPyTransform.Exception.ProcessMissing"));
    }
  }

  private Context createContext() {
    HostAccess hostAccess = HostAccess.newBuilder(HostAccess.EXPLICIT).build();
    return Context.newBuilder("python")
        .allowHostAccess(hostAccess)
        .allowHostClassLookup(className -> false)
        .allowIO(IOAccess.NONE)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .allowCreateProcess(false)
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .option("engine.WarnInterpreterOnly", "false")
        .build();
  }

  private <T> T withPluginClassLoader(CheckedSupplier<T> supplier) throws HopException {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    try {
      return supplier.get();
    } catch (HopException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new HopException(e);
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }
}
