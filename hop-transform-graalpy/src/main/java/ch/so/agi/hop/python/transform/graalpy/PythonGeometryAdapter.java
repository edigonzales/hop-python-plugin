package ch.so.agi.hop.python.transform.graalpy;

import java.lang.reflect.*;
import java.util.*;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.value.ValueMetaFactory;
import org.graalvm.polyglot.HostAccess.Export;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.*;

/** Optional bridge to the *registered* geometry plugin. No JTS classes cross into this loader. */
public final class PythonGeometryAdapter implements PythonTypeAdapter {
  static final int TYPE = 43663879;
  private final HopPythonTypeBridge bridge;
  private Class<?> geometryClass, curves, linear;
  private Object wktReader, xyWriter;
  private Method readWkt, writeXy;
  private final Map<String, Method> methods = new HashMap<>();
  private boolean initialized;

  PythonGeometryAdapter(HopPythonTypeBridge bridge) {
    this.bridge = bridge;
  }

  void initialize() throws Exception {
    if (initialized) return;
    IValueMeta metadata = ValueMetaFactory.createValueMeta(TYPE);
    if (!metadata.getClass().getName().equals("com.atolcd.hop.core.row.value.ValueMetaGeometry"))
      throw new IllegalStateException("Compatible hop-geometry-type 0.2 plugin is required");
    ClassLoader loader = metadata.getClass().getClassLoader();
    geometryClass = Class.forName("org.locationtech.jts.geom.Geometry", true, loader);
    curves = Class.forName("com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport", true, loader);
    linear = Class.forName("com.atolcd.hop.gis.geometry.curve.LinearGeometryCodec", true, loader);
    for (String name : List.of("copy", "writeWkb", "writeWkt", "isCurveGeometry"))
      methods.put("curve." + name, curves.getMethod(name, geometryClass));
    methods.put("curve.readWkb", curves.getMethod("readWkb", byte[].class));
    methods.put("linear.writeWkt", linear.getMethod("writeWkt", geometryClass));
    methods.put("linear.ordinates", linear.getMethod("ordinates", geometryClass));
    for (String name :
        List.of(
            "getArea",
            "getLength",
            "isValid",
            "isEmpty",
            "getGeometryType",
            "getSRID",
            "getCentroid",
            "getNumGeometries")) methods.put(name, geometryClass.getMethod(name));
    methods.put("buffer", geometryClass.getMethod("buffer", double.class, int.class));
    methods.put("setSRID", geometryClass.getMethod("setSRID", int.class));
    methods.put("getGeometryN", geometryClass.getMethod("getGeometryN", int.class));
    Class<?> reader = Class.forName("org.locationtech.jts.io.WKTReader", true, loader);
    wktReader = reader.getConstructor().newInstance();
    reader.getMethod("setIsOldJtsCoordinateSyntaxAllowed", boolean.class).invoke(wktReader, false);
    readWkt = reader.getMethod("read", String.class);
    Class<?> writer = Class.forName("org.locationtech.jts.io.WKBWriter", true, loader);
    xyWriter = writer.getConstructor(int.class).newInstance(2);
    writeXy = writer.getMethod("write", geometryClass);
    initialized = true;
  }

  private Object invoke(String method, Object target, Object... args) {
    try {
      return methods.get(method).invoke(target, args);
    } catch (InvocationTargetException e) {
      throw new IllegalArgumentException(
          "Geometry " + method + ": " + e.getCause().getMessage(), e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Incompatible geometry plugin", e);
    }
  }

  private void requireAvailable() {
    try {
      initialize();
    } catch (Exception e) {
      throw new IllegalStateException("Compatible hop-geometry-type 0.2 plugin is required", e);
    }
  }

  private Object snapshot(Object geometry) {
    return invoke("curve.copy", null, geometry);
  }

  private boolean hasCurves(Object geometry) {
    if ((Boolean) invoke("curve.isCurveGeometry", null, geometry)) return true;
    int count = (Integer) invoke("getNumGeometries", geometry);
    for (int i = 0; i < count; i++) {
      Object child = invoke("getGeometryN", geometry, i);
      if (child != geometry && hasCurves(child)) return true;
    }
    return false;
  }

  private void requireLinear2d(Object geometry) {
    if (hasCurves(geometry) || ((Set<?>) invoke("linear.ordinates", null, geometry)).size() > 2)
      throw new IllegalArgumentException(
          "Geometry calculations require linear 2D input; curves and Z/M are not implicitly"
              + " reduced");
  }

  @Override
  public Object toPython(IValueMeta metadata, Object value) throws Exception {
    initialize();
    Object geometry =
        metadata.getClass().getMethod("getGeometry", Object.class).invoke(metadata, value);
    if (geometry == null) return null;
    return new GeometryValue(snapshot(geometry));
  }

  @Override
  public Object fromPython(String field, IValueMeta metadata, Value value) throws Exception {
    initialize();
    if (!value.isProxyObject()
        || !(value.asProxyObject() instanceof PythonGeometryAdapter.GeometryValue wrapped)
        || wrapped.owner() != this)
      throw new IllegalArgumentException(
          "Geometry output requires a geometry wrapper or None: " + field);
    return snapshot(wrapped.geometry);
  }

  @Export
  public Object fromWkt(String text, int srid) throws Exception {
    initialize();
    if (text == null
        || text.matches(
            "(?is).*\\b(CIRCULARSTRING|COMPOUNDCURVE|CURVEPOLYGON|MULTICURVE|MULTISURFACE)\\b.*"))
      throw new IllegalArgumentException("Only linear WKT input is supported; use EWKB for curves");
    Object geometry;
    try {
      geometry = readWkt.invoke(wktReader, text);
    } catch (InvocationTargetException e) {
      throw new IllegalArgumentException("Invalid WKT: " + e.getCause().getMessage(), e.getCause());
    }
    invoke("setSRID", geometry, srid);
    return new GeometryValue(geometry);
  }

  @Export
  public Object fromWkb(Value bytes, Value srid) throws Exception {
    initialize();
    Object geometry = invoke("curve.readWkb", null, bridge.fromPythonBytes(bytes));
    if (srid != null && !srid.isNull()) invoke("setSRID", geometry, srid.asInt());
    return new GeometryValue(geometry);
  }

  Object fromWkbBytes(byte[] bytes) {
    requireAvailable();
    return invoke("curve.readWkb", null, bytes);
  }

  byte[] toWkbBytes(Object geometry) {
    requireAvailable();
    return (byte[]) invoke("curve.writeWkb", null, geometry);
  }

  String toWktText(Object geometry) {
    requireAvailable();
    return (String)
        invoke(
            (Boolean) invoke("curve.isCurveGeometry", null, geometry)
                ? "curve.writeWkt"
                : "linear.writeWkt",
            null,
            geometry);
  }

  private final class GeometryValue implements ProxyObject {
    private final Object geometry;

    GeometryValue(Object geometry) {
      this.geometry = geometry;
    }

    PythonGeometryAdapter owner() {
      return PythonGeometryAdapter.this;
    }

    private final Set<String> keys =
        Set.of(
            "area",
            "length",
            "is_valid",
            "is_empty",
            "geom_type",
            "srid",
            "buffer",
            "centroid",
            "to_wkt",
            "to_wkb");

    @Override
    public Object getMember(String key) {
      return switch (key) {
        case "area", "length", "is_valid" -> {
          requireLinear2d(geometry);
          yield invoke(
              key.equals("area") ? "getArea" : key.equals("length") ? "getLength" : "isValid",
              geometry);
        }
        case "is_empty" -> invoke("isEmpty", geometry);
        case "geom_type" -> invoke("getGeometryType", geometry);
        case "srid" -> invoke("getSRID", geometry);
        case "to_wkt" ->
            (ProxyExecutable)
                args -> {
                  arity(args, 0);
                  return toWktText(geometry);
                };
        case "to_wkb" ->
            (ProxyExecutable)
                args -> {
                  arity(args, 0);
                  return bridge.toPythonBytes(toWkbBytes(geometry));
                };
        case "centroid", "buffer" ->
            (ProxyExecutable)
                args -> {
                  arity(args, key.equals("buffer") ? 1 : 0);
                  requireLinear2d(geometry);
                  Object result;
                  if (key.equals("buffer")) {
                    double distance = args[0].asDouble();
                    if (!Double.isFinite(distance))
                      throw new IllegalArgumentException("Buffer distance must be finite");
                    result = invoke("buffer", geometry, distance, 8);
                  } else result = invoke("getCentroid", geometry);
                  // JTS algorithms may allocate XYZ sequences with NaN Z for XY calculations.
                  // Their result contract here is explicitly XY, unlike imported geometries.
                  try {
                    result =
                        invoke("curve.readWkb", null, (byte[]) writeXy.invoke(xyWriter, result));
                  } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot encode XY geometry result", e);
                  }
                  invoke("setSRID", result, invoke("getSRID", geometry));
                  return new GeometryValue(result);
                };
        default -> throw new IllegalArgumentException("Unknown geometry property: " + key);
      };
    }

    private void arity(Value[] args, int length) {
      if (args.length != length)
        throw new IllegalArgumentException("Wrong geometry argument count");
    }

    @Override
    public Object getMemberKeys() {
      return keys.toArray(String[]::new);
    }

    @Override
    public boolean hasMember(String key) {
      return keys.contains(key);
    }

    @Override
    public void putMember(String key, Value value) {
      throw new UnsupportedOperationException("Geometry is read-only");
    }

    @Override
    public boolean removeMember(String key) {
      throw new UnsupportedOperationException("Geometry is read-only");
    }
  }
}
