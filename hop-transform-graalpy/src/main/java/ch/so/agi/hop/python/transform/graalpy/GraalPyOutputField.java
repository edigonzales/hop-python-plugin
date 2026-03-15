package ch.so.agi.hop.python.transform.graalpy;

import java.util.List;
import org.apache.hop.core.exception.HopPluginException;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.value.ValueMetaFactory;
import org.apache.hop.metadata.api.HopMetadataProperty;

public class GraalPyOutputField {
  public static final List<Integer> SUPPORTED_TYPES =
      List.of(
          IValueMeta.TYPE_STRING,
          IValueMeta.TYPE_INTEGER,
          IValueMeta.TYPE_NUMBER,
          IValueMeta.TYPE_BIGNUMBER,
          IValueMeta.TYPE_DATE,
          IValueMeta.TYPE_TIMESTAMP,
          IValueMeta.TYPE_BOOLEAN);

  @HopMetadataProperty private String name;
  @HopMetadataProperty private String type;
  @HopMetadataProperty private int length;
  @HopMetadataProperty private int precision;
  @HopMetadataProperty private boolean replaceExisting;

  public GraalPyOutputField() {
    this.type = ValueMetaFactory.getValueMetaName(IValueMeta.TYPE_STRING);
  }

  public GraalPyOutputField(GraalPyOutputField other) {
    this.name = other.name;
    this.type = other.type;
    this.length = other.length;
    this.precision = other.precision;
    this.replaceExisting = other.replaceExisting;
  }

  public static String[] supportedTypeNames() {
    return SUPPORTED_TYPES.stream().map(ValueMetaFactory::getValueMetaName).toArray(String[]::new);
  }

  public int getHopType() {
    return ValueMetaFactory.getIdForValueMeta(type);
  }

  public IValueMeta createValueMeta() throws HopPluginException {
    IValueMeta valueMeta = ValueMetaFactory.createValueMeta(name, getHopType());
    valueMeta.setLength(length);
    valueMeta.setPrecision(precision);
    return valueMeta;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public int getLength() {
    return length;
  }

  public void setLength(int length) {
    this.length = length;
  }

  public int getPrecision() {
    return precision;
  }

  public void setPrecision(int precision) {
    this.precision = precision;
  }

  public boolean isReplaceExisting() {
    return replaceExisting;
  }

  public void setReplaceExisting(boolean replaceExisting) {
    this.replaceExisting = replaceExisting;
  }
}
