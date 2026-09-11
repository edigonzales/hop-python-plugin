package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.pipeline.transform.BaseTransformData;
import org.apache.hop.pipeline.transform.ITransformData;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

@SuppressWarnings("java:S1104")
public class GraalPyTransformData extends BaseTransformData implements ITransformData {
  public Context context;
  public Value processFunction;
  public IRowMeta inputRowMeta;
  public IRowMeta outputRowMeta;
  public HopPythonTypeBridge typeBridge;
  public OutputRowAssembler outputRowAssembler;
  public PyContextFacade pyContext;
  public GraalPyExternalEnvironment externalEnvironment;
}
