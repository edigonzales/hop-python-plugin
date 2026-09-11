package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.pipeline.transform.BaseTransformData;
import org.apache.hop.pipeline.transform.ITransformData;

public class GraalPyTransformData extends BaseTransformData implements ITransformData {
  public volatile PythonRuntimeSession session;
}
