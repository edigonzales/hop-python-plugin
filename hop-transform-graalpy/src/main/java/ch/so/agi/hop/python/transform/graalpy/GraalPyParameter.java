package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.metadata.api.HopMetadataProperty;

public class GraalPyParameter {
  @HopMetadataProperty private String name = "";
  @HopMetadataProperty private String value = "";

  public GraalPyParameter() {}

  public GraalPyParameter(String name, String value) {
    this.name = name;
    this.value = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }
}
