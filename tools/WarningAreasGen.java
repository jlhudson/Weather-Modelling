import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.coverage.CoverageSimplifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.precision.GeometryPrecisionReducer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The areas the Bureau's warnings name, as shapes (W-50): South Australia's and Tasmania's public forecast districts,
 * fire weather districts and marine zones, from the Bureau's own shapefiles, simplified and written as one GeoJSON
 * resource keyed by AMOC area code. Run by hand when the Bureau changes a file; the service only reads what it wrote.
 * <pre>
 * curl -O ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00001.zip   # public weather forecast districts (PW)
 * curl -O ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00007.zip   # fire weather districts (FW)
 * curl -O ftp://ftp.bom.gov.au/anon/home/adfd/spatial/IDM00003.zip   # marine zones (MW)
 * java -cp ~/.m2/repository/org/locationtech/jts/jts-core/1.20.0/jts-core-1.20.0.jar tools/WarningAreasGen.java \
 *      &lt;folder with the zips&gt; src/main/resources/bureau/warning-areas.geojson
 * </pre>
 * Each product is a shapefile (.shp polygons, .dbf attributes, the same order) in GDA94 longitude and latitude, read here
 * without a GIS library: the formats are a page each. Each product's districts are simplified together as a coverage, so
 * neighbours keep one shared edge and a union of them has no slivers, then rounded to 4 decimal places (about 10 m).
 */
public class WarningAreasGen {

    /** The products, the states kept, and what each area is called: the Bureau's own words for the layer. */
    record Product(String id, String type) {
    }

    static final List<Product> PRODUCTS = List.of(
            new Product("IDM00001", "forecast district"),
            new Product("IDM00007", "fire weather district"),
            new Product("IDM00003", "waters"));
    static final List<String> STATES = List.of("SA", "TAS");
    /** About 500 m: a district's outline on a state map, not a cadastre. */
    static final double TOLERANCE = 0.005;
    static final double SCALE = 1e4;

    static final GeometryFactory FACTORY = new GeometryFactory();

    record Feature(String aac, String name, String state, String type, String source, Geometry shape) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: WarningAreasGen <folder with IDM00001.zip, IDM00007.zip, IDM00003.zip> <out.geojson>");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        List<Feature> all = new ArrayList<>();
        List<String> editions = new ArrayList<>();
        for (Product p : PRODUCTS) {
            Map<String, byte[]> files = unzip(in.resolve(p.id() + ".zip"));
            editions.add(p.id() + " of " + new String(files.get("date"), StandardCharsets.US_ASCII));
            List<Map<String, String>> rows = dbf(files.get(p.id() + ".dbf"));
            List<Geometry> shapes = shp(files.get(p.id() + ".shp"));
            if (rows.size() != shapes.size()) {
                throw new IllegalStateException(p.id() + ": " + rows.size() + " rows but " + shapes.size() + " shapes");
            }
            List<Feature> kept = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> row = rows.get(i);
                String aac = row.get("AAC"), state = row.get("STATE_CODE");
                if (aac == null || !STATES.contains(state) || shapes.get(i) == null) {
                    continue;
                }
                // A marine zone says whether it is coastal, local or inland waters; the rest are one kind each.
                String type = p.type().equals("waters") ? row.get("TYPE").toLowerCase(Locale.ROOT) + " waters" : p.type();
                kept.add(new Feature(aac, row.get("DIST_NAME"), state.toLowerCase(Locale.ROOT), type, p.id(), shapes.get(i)));
            }
            Geometry[] simplified = CoverageSimplifier.simplify(kept.stream().map(Feature::shape).toArray(Geometry[]::new), TOLERANCE);
            GeometryPrecisionReducer reducer = new GeometryPrecisionReducer(new PrecisionModel(SCALE));
            for (int i = 0; i < kept.size(); i++) {
                Feature f = kept.get(i);
                Geometry g = reducer.reduce(simplified[i]);
                if (!g.isValid()) {
                    g = GeometryFixer.fix(g);
                }
                if (g.isEmpty()) {
                    throw new IllegalStateException(f.aac() + " simplified to nothing");
                }
                all.add(new Feature(f.aac(), f.name(), f.state(), f.type(), f.source(), g));
                System.err.printf("%s %-10s %-40s %5d -> %5d points%n", p.id(), f.aac(), f.name(), f.shape().getNumPoints(), g.getNumPoints());
            }
        }
        all.sort(Comparator.comparing(Feature::aac));
        StringBuilder out = new StringBuilder();
        out.append("{\"type\":\"FeatureCollection\",\n\"source\":\"Bureau of Meteorology spatial data, ftp://ftp.bom.gov.au/anon/home/adfd/spatial/: ")
                .append("IDM00001 public weather forecast districts, IDM00007 fire weather districts, IDM00003 marine zones (")
                .append(String.join(", ", editions)).append("); ")
                .append("South Australia and Tasmania, simplified to ").append(TOLERANCE).append(" degrees as coverages, 4 decimal places. ")
                .append("Written by tools/WarningAreasGen.java (W-50).\",\n\"features\":[\n");
        for (int i = 0; i < all.size(); i++) {
            Feature f = all.get(i);
            out.append("{\"type\":\"Feature\",\"id\":\"").append(f.aac()).append("\",\"properties\":{\"aac\":\"").append(f.aac())
                    .append("\",\"name\":\"").append(f.name().replace("\"", "'")).append("\",\"state\":\"").append(f.state())
                    .append("\",\"type\":\"").append(f.type()).append("\",\"source\":\"").append(f.source()).append("\"},\"geometry\":");
            geometry(out, f.shape());
            out.append(i + 1 < all.size() ? "},\n" : "}\n");
        }
        out.append("]}\n");
        Files.writeString(Path.of(args[1]), out, StandardCharsets.UTF_8);
        System.err.printf("%d areas, %,d bytes%n", all.size(), out.length());
    }

    // ---------------------------------------------------------------- the files

    /**
     * A product's files by name, and under {@code date} the day its shapes were written, as the zip records it.
     */
    static Map<String, byte[]> unzip(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (InputStream raw = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(raw)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String name = Path.of(e.getName()).getFileName().toString();
                out.put(name, z.readAllBytes());
                if (name.endsWith(".shp")) {
                    out.put("date", e.getTimeLocal().toLocalDate().toString().getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
        return out;
    }

    /**
     * A dBase III table: a 32-byte header (records, header length, record length), 32 bytes per field to a 0x0D, then
     * fixed-width records each led by a deletion flag.
     */
    static List<Map<String, String>> dbf(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int records = bb.getInt(4), header = bb.getShort(8) & 0xffff, length = bb.getShort(10) & 0xffff;
        List<String> names = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        for (int off = 32; b[off] != 0x0d; off += 32) {
            names.add(new String(b, off, 11, StandardCharsets.ISO_8859_1).replace("\0", "").trim());
            widths.add(b[off + 16] & 0xff);
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (int r = 0; r < records; r++) {
            int p = header + r * length;
            boolean deleted = b[p] == '*';
            p++;
            Map<String, String> row = new LinkedHashMap<>();
            for (int f = 0; f < names.size(); f++) {
                row.put(names.get(f), new String(b, p, widths.get(f), StandardCharsets.ISO_8859_1).trim());
                p += widths.get(f);
            }
            // A deleted record still has its shape in the .shp, so it keeps its place in the list, without a code.
            if (deleted) {
                row.remove("AAC");
            }
            out.add(row);
        }
        return out;
    }

    /**
     * An Esri shapefile's polygons, in record order: a 100-byte header, then per record a big-endian number and length and
     * a little-endian shape - its type (5 polygon, 15 with Z, 25 with M), box, part and point counts, where each part
     * starts, and the points. An outer ring runs clockwise, a hole anticlockwise; a hole belongs to the ring holding it.
     */
    static List<Geometry> shp(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        int end = bb.order(ByteOrder.BIG_ENDIAN).getInt(24) * 2;
        List<Geometry> out = new ArrayList<>();
        int p = 100;
        while (p < end) {
            int length = bb.order(ByteOrder.BIG_ENDIAN).getInt(p + 4) * 2;
            int c = p + 8;
            bb.order(ByteOrder.LITTLE_ENDIAN);
            int type = bb.getInt(c);
            if (type == 0) {
                out.add(null);
            } else if (type == 5 || type == 15 || type == 25) {
                int parts = bb.getInt(c + 36), points = bb.getInt(c + 40);
                int[] starts = new int[parts + 1];
                for (int i = 0; i < parts; i++) {
                    starts[i] = bb.getInt(c + 44 + 4 * i);
                }
                starts[parts] = points;
                int xy = c + 44 + 4 * parts;
                List<LinearRing> shells = new ArrayList<>(), holes = new ArrayList<>();
                for (int i = 0; i < parts; i++) {
                    List<Coordinate> ring = new ArrayList<>();
                    for (int k = starts[i]; k < starts[i + 1]; k++) {
                        ring.add(new Coordinate(bb.getDouble(xy + 16 * k), bb.getDouble(xy + 16 * k + 8)));
                    }
                    if (ring.size() < 4) {
                        continue;
                    }
                    if (!ring.getFirst().equals2D(ring.getLast())) {
                        ring.add(ring.getFirst().copy());
                    }
                    LinearRing r = FACTORY.createLinearRing(ring.toArray(Coordinate[]::new));
                    (Orientation.isCCW(r.getCoordinates()) ? holes : shells).add(r);
                }
                List<Polygon> polygons = new ArrayList<>();
                for (LinearRing shell : shells) {
                    Polygon plain = FACTORY.createPolygon(shell);
                    LinearRing[] inside = holes.stream().filter(h -> plain.covers(FACTORY.createPoint(h.getCoordinateN(0)))).toArray(LinearRing[]::new);
                    polygons.add(FACTORY.createPolygon(shell, inside));
                }
                Geometry g = FACTORY.createMultiPolygon(polygons.toArray(Polygon[]::new));
                out.add(g.isValid() ? g : GeometryFixer.fix(g));
            } else {
                throw new IllegalStateException("shape type " + type + " is not a polygon");
            }
            p += 8 + length;
        }
        return out;
    }

    // ---------------------------------------------------------------- GeoJSON

    /**
     * A Polygon or MultiPolygon, outer rings anticlockwise and holes clockwise as RFC 7946 asks.
     */
    static void geometry(StringBuilder out, Geometry g) {
        List<Polygon> polygons = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (g.getGeometryN(i) instanceof Polygon p && !p.isEmpty()) {
                polygons.add(p);
            }
        }
        boolean multi = polygons.size() > 1;
        out.append("{\"type\":\"").append(multi ? "MultiPolygon" : "Polygon").append("\",\"coordinates\":");
        if (multi) {
            out.append('[');
        }
        for (int i = 0; i < polygons.size(); i++) {
            Polygon p = polygons.get(i);
            out.append(i == 0 ? "" : ",").append('[');
            ring(out, p.getExteriorRing().getCoordinates(), true);
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                out.append(',');
                ring(out, p.getInteriorRingN(h).getCoordinates(), false);
            }
            out.append(']');
        }
        if (multi) {
            out.append(']');
        }
        out.append('}');
    }

    static void ring(StringBuilder out, Coordinate[] cs, boolean anticlockwise) {
        if (Orientation.isCCW(cs) != anticlockwise) {
            cs = cs.clone();
            java.util.Collections.reverse(java.util.Arrays.asList(cs));
        }
        out.append('[');
        for (int i = 0; i < cs.length; i++) {
            out.append(i == 0 ? "" : ",").append('[').append(number(cs[i].x)).append(',').append(number(cs[i].y)).append(']');
        }
        out.append(']');
    }

    static String number(double v) {
        double r = Math.round(v * SCALE) / SCALE;
        String s = String.format(Locale.ROOT, "%.4f", r);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
