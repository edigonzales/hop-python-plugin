package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.*;

import com.atolcd.hop.core.row.value.ValueMetaGeometry;
import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.RowMeta;
import org.junit.jupiter.api.*;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

class PythonGeometryTest {
  static WKTReader reader() {
    WKTReader r = new WKTReader();
    r.setIsOldJtsCoordinateSyntaxAllowed(false);
    return r;
  }

  @BeforeAll
  static void initialize() throws Exception {
    HopEnvironment.init();
  }

  @AfterAll
  static void reset() {
    HopEnvironment.reset();
  }

  @Test
  void computesAndCreatesGeometryInRegisteredRuntime() throws Exception {
    GraalPyTransformMeta meta =
        PythonRuntimeSessionTest.meta(
            """
        def process(row, ctx):
            g = row['geom']
            assert g.area == 100.0 and g.length == 40.0 and g.is_valid
            assert g.geom_type == 'Polygon' and g.srid == 2056
            p = ctx.geometry.from_wkt('POINT (0 0)', srid=2056)
            assert p.buffer(1).area > 3
            assert p.is_empty is False
            copy = ctx.geometry.from_wkb(g.to_wkb())
            assert copy.srid == 2056 and copy.area == 100
            return {'geom': g.centroid(), 'new': p.buffer(1)}
        """);
    meta.setOutputFields(
        List.of(
            PythonRuntimeSessionTest.field("geom", "Geometry", true),
            PythonRuntimeSessionTest.field("new", "Geometry", false)));
    Geometry input = reader().read("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
    input.setSRID(2056);
    RowMeta metadata = new RowMeta();
    metadata.addValueMeta(new ValueMetaGeometry("geom"));
    try (PythonRuntimeSession session = PythonRuntimeSessionTest.session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(metadata);
      Object[] out = session.process(new Object[] {input}, 1).rows().get(0);
      Geometry centroid = (Geometry) out[0];
      Geometry buffer = (Geometry) out[1];
      assertEquals("POINT (5 5)", centroid.toText());
      assertEquals(2056, centroid.getSRID());
      assertEquals(2056, buffer.getSRID());
      assertEquals(100, input.getArea());
      assertNotSame(input, centroid);
    }
  }

  @Test
  void preservesEmptyAndDimensionsAndRejectsImplicitReduction() throws Exception {
    for (String wkt :
        List.of("POINT EMPTY", "POINT Z (1 2 3)", "POINT M (1 2 4)", "POINT ZM EMPTY")) {
      Geometry geometry = reader().read(wkt);
      geometry.setSRID(2056);
      GraalPyTransformMeta meta =
          PythonRuntimeSessionTest.meta(
              """
          def process(row, ctx):
              g = row['geom']
              h = ctx.geometry.from_wkb(g.to_wkb())
              assert h.is_empty == g.is_empty
              return {'geom': h}
          """);
      meta.setOutputFields(List.of(PythonRuntimeSessionTest.field("geom", "Geometry", true)));
      RowMeta metadata = new RowMeta();
      metadata.addValueMeta(new ValueMetaGeometry("geom"));
      try (PythonRuntimeSession session =
          PythonRuntimeSessionTest.session(meta, new ArrayList<>())) {
        session.open();
        session.prepare(metadata);
        Geometry out = (Geometry) session.process(new Object[] {geometry}, 1).rows().get(0)[0];
        assertArrayEquals(
            CurveGeometrySupport.writeWkb(geometry), CurveGeometrySupport.writeWkb(out));
        assertNotSame(geometry, out);
      }
    }
    GraalPyTransformMeta meta =
        PythonRuntimeSessionTest.meta(
            "def process(row, ctx):\n"
                + "    g = ctx.geometry.from_wkt('POINT Z (1 2 3)')\n"
                + "    return {'area': g.area}");
    meta.setOutputFields(List.of(PythonRuntimeSessionTest.field("area", "Number", false)));
    try (PythonRuntimeSession session = PythonRuntimeSessionTest.session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(new RowMeta());
      assertTrue(
          assertThrows(Exception.class, () -> session.process(new Object[0], 1))
              .getMessage()
              .contains("linear 2D"));
    }
  }

  @Test
  void curvedEwkbRoundtripAndOperationsFailExplicitly() throws Exception {
    // Little-endian CircularString with SRID 2056 and three XY control points.
    java.nio.ByteBuffer b =
        java.nio.ByteBuffer.allocate(1 + 4 + 4 + 4 + 3 * 16)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.put((byte) 1).putInt(0x20000008).putInt(2056).putInt(3);
    b.putDouble(0).putDouble(0).putDouble(1).putDouble(1).putDouble(2).putDouble(0);
    Geometry curve = CurveGeometrySupport.readWkb(b.array());
    GraalPyTransformMeta meta =
        PythonRuntimeSessionTest.meta(
            """
        def process(row, ctx):
            g = row['geom']
            assert 'CIRCULARSTRING' in g.to_wkt().upper()
            return {'geom': ctx.geometry.from_wkb(g.to_wkb())}
        """);
    meta.setOutputFields(List.of(PythonRuntimeSessionTest.field("geom", "Geometry", true)));
    RowMeta metadata = new RowMeta();
    metadata.addValueMeta(new ValueMetaGeometry("geom"));
    try (PythonRuntimeSession session = PythonRuntimeSessionTest.session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(metadata);
      Geometry out = (Geometry) session.process(new Object[] {curve}, 1).rows().get(0)[0];
      assertArrayEquals(CurveGeometrySupport.writeWkb(curve), CurveGeometrySupport.writeWkb(out));
    }
    meta.setScriptText("def process(row, ctx):\n    row['geom'].centroid()\n    return {}");
    try (PythonRuntimeSession session = PythonRuntimeSessionTest.session(meta, new ArrayList<>())) {
      session.open();
      session.prepare(metadata);
      assertTrue(
          assertThrows(Exception.class, () -> session.process(new Object[] {curve}, 1))
              .getMessage()
              .contains("linear 2D"));
    }
  }
}
