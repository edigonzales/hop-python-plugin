package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.core.row.IValueMeta;
import org.graalvm.polyglot.Value;

interface PythonTypeAdapter {
  Object toPython(IValueMeta metadata, Object value) throws Exception;

  Object fromPython(String field, IValueMeta metadata, Value value) throws Exception;
}
