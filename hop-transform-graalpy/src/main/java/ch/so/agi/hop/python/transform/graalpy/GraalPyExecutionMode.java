package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.metadata.api.IEnumHasCodeAndDescription;

public enum GraalPyExecutionMode implements IEnumHasCodeAndDescription {
  RETURN_ONE("RETURN_ONE", "Return one row"),
  EMIT_MANY("EMIT_MANY", "Emit zero or more rows");

  private final String code;
  private final String description;

  GraalPyExecutionMode(String code, String description) {
    this.code = code;
    this.description = description;
  }

  @Override
  public String getCode() {
    return code;
  }

  @Override
  public String getDescription() {
    return description;
  }

  public static GraalPyExecutionMode fromCode(String code) {
    for (GraalPyExecutionMode mode : values()) {
      if (mode.code.equalsIgnoreCase(code)) {
        return mode;
      }
    }
    return RETURN_ONE;
  }
}
