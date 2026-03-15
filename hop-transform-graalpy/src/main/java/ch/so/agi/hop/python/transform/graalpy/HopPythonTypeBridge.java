package ch.so.agi.hop.python.transform.graalpy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.exception.HopValueException;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.i18n.BaseMessages;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public final class HopPythonTypeBridge {
  private static final Class<?> PKG = GraalPyTransform.class;

  private final Value dictFactory;
  private final Value decimalFactory;
  private final Value datetimeFactory;
  private final Value stringFunction;
  private final ZoneId zoneId;

  public HopPythonTypeBridge(Context context) {
    this.dictFactory = context.eval("python", "dict");
    this.decimalFactory = context.eval("python", "import decimal\ndecimal.Decimal");
    this.datetimeFactory = context.eval("python", "import datetime\ndatetime.datetime");
    this.stringFunction = context.eval("python", "str");
    this.zoneId = ZoneId.systemDefault();
  }

  public void validateInputTypes(IRowMeta inputRowMeta) throws HopTransformException {
    if (inputRowMeta == null) {
      return;
    }
    for (int i = 0; i < inputRowMeta.size(); i++) {
      IValueMeta valueMeta = inputRowMeta.getValueMeta(i);
      if (!GraalPyOutputField.SUPPORTED_TYPES.contains(valueMeta.getType())) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG,
                "GraalPyTransform.Exception.UnsupportedInputType",
                valueMeta.getName(),
                valueMeta.getTypeDesc()));
      }
    }
  }

  public Value createPythonRow(IRowMeta rowMeta, Object[] row) throws HopValueException {
    Value pythonRow = dictFactory.execute();
    if (rowMeta == null || row == null) {
      return pythonRow;
    }
    for (int i = 0; i < rowMeta.size(); i++) {
      pythonRow.putHashEntry(rowMeta.getValueMeta(i).getName(), toPythonValue(rowMeta.getValueMeta(i), row[i]));
    }
    return pythonRow;
  }

  public Map<String, Object> snapshotMapping(Value mapping, OutputRowAssembler assembler)
      throws HopTransformException {
    if (mapping == null || mapping.isNull() || !mapping.hasHashEntries()) {
      throw new HopTransformException(
          BaseMessages.getString(
              PKG, "GraalPyTransform.Exception.OutputMappingExpected", describeValue(mapping)));
    }

    Map<String, Object> snapshot = new LinkedHashMap<>();
    Value iterator = mapping.getHashEntriesIterator();
    while (iterator.hasIteratorNextElement()) {
      Value entry = iterator.getIteratorNextElement();
      Value keyValue = entry.getArrayElement(0);
      if (!keyValue.isString()) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG, "GraalPyTransform.Exception.InvalidOutputKey", keyValue.toString()));
      }
      String fieldName = keyValue.asString();
      if (!assembler.getAllowedFields().contains(fieldName)) {
        throw new HopTransformException(
            BaseMessages.getString(
                PKG, "GraalPyTransform.Exception.UnknownOutputField", fieldName));
      }
      snapshot.put(
          fieldName,
          fromPythonValue(fieldName, assembler.getOutputValueMeta(fieldName), entry.getArrayElement(1)));
    }
    return snapshot;
  }

  private Object toPythonValue(IValueMeta valueMeta, Object value) throws HopValueException {
    if (value == null) {
      return null;
    }

    return switch (valueMeta.getType()) {
      case IValueMeta.TYPE_STRING -> valueMeta.getString(value);
      case IValueMeta.TYPE_INTEGER -> valueMeta.getInteger(value);
      case IValueMeta.TYPE_NUMBER -> valueMeta.getNumber(value);
      case IValueMeta.TYPE_BIGNUMBER -> decimalFactory.execute(valueMeta.getBigNumber(value).toPlainString());
      case IValueMeta.TYPE_BOOLEAN -> valueMeta.getBoolean(value);
      case IValueMeta.TYPE_DATE, IValueMeta.TYPE_TIMESTAMP -> {
        Date date = valueMeta.getDate(value);
        LocalDateTime localDateTime = LocalDateTime.ofInstant(date.toInstant(), zoneId);
        yield datetimeFactory.execute(
            localDateTime.getYear(),
            localDateTime.getMonthValue(),
            localDateTime.getDayOfMonth(),
            localDateTime.getHour(),
            localDateTime.getMinute(),
            localDateTime.getSecond(),
            localDateTime.getNano() / 1_000);
      }
      default -> throw new HopValueException("Unsupported input type " + valueMeta.getTypeDesc());
    };
  }

  private Object fromPythonValue(String fieldName, IValueMeta valueMeta, Value value)
      throws HopTransformException {
    if (value == null || value.isNull()) {
      return null;
    }

    try {
      return switch (valueMeta.getType()) {
        case IValueMeta.TYPE_STRING -> value.isString() ? value.asString() : stringFunction.execute(value).asString();
        case IValueMeta.TYPE_INTEGER -> toLong(value);
        case IValueMeta.TYPE_NUMBER -> toDouble(value);
        case IValueMeta.TYPE_BIGNUMBER -> toBigDecimal(value);
        case IValueMeta.TYPE_BOOLEAN -> {
          if (!value.isBoolean()) {
            throw new HopTransformException("Expected bool");
          }
          yield value.asBoolean();
        }
        case IValueMeta.TYPE_DATE, IValueMeta.TYPE_TIMESTAMP -> toDate(value);
        default ->
            throw new HopTransformException(
                "Unsupported output type " + valueMeta.getTypeDesc() + " for " + fieldName);
      };
    } catch (Exception e) {
      throw new HopTransformException("Unable to convert Python value for field '" + fieldName + "'", e);
    }
  }

  private Long toLong(Value value) {
    if (value.fitsInLong()) {
      return value.asLong();
    }
    if (value.fitsInBigInteger()) {
      return value.asBigInteger().longValueExact();
    }
    if (value.isNumber()) {
      return BigDecimal.valueOf(value.asDouble()).longValueExact();
    }
    return new BigDecimal(stringFunction.execute(value).asString()).longValueExact();
  }

  private Double toDouble(Value value) {
    if (value.fitsInDouble()) {
      return value.asDouble();
    }
    if (value.fitsInBigInteger()) {
      return value.asBigInteger().doubleValue();
    }
    if (value.isNumber()) {
      return value.as(Number.class).doubleValue();
    }
    return Double.valueOf(stringFunction.execute(value).asString());
  }

  private BigDecimal toBigDecimal(Value value) {
    if (value.isHostObject() && value.asHostObject() instanceof BigDecimal bigDecimal) {
      return bigDecimal;
    }
    if (value.fitsInBigInteger()) {
      return new BigDecimal(value.asBigInteger());
    }
    if (value.isNumber() && value.fitsInDouble()) {
      return BigDecimal.valueOf(value.asDouble());
    }
    return new BigDecimal(stringFunction.execute(value).asString());
  }

  private Date toDate(Value value) {
    if (value.isInstant()) {
      return Date.from(value.asInstant());
    }

    LocalDate date = value.asDate();
    LocalTime time = value.isTime() ? value.asTime() : LocalTime.MIDNIGHT;
    Instant instant = ZonedDateTime.of(date, time, zoneId).toInstant();
    return Date.from(instant);
  }

  public String describeValue(Value value) {
    if (value == null) {
      return "null";
    }
    if (value.isNull()) {
      return "None";
    }
    if (value.getMetaObject() != null) {
      return value.getMetaObject().getMetaSimpleName();
    }
    return value.toString();
  }
}
