package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.metadata.api.HopMetadataProperty;

public class GraalPyInputField {
  @HopMetadataProperty private String name = "";

  public GraalPyInputField() {}

  public GraalPyInputField(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }
}
