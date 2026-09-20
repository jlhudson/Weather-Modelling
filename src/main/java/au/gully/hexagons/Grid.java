package au.gully.hexagons;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The hexagon generator (docs/06 item 1). Australia is divided into flat-topped hexagons
 * {@link #CELL_KM} across the flats, laid out from one {@link #ANCHOR_LAT anchor} — hexagon {@code 0_0}
 * is centred on it — and every reading belongs to one. The hexagons are not stored anywhere and never
 * generated as a list: any point's hexagon is arithmetic on the Albers plane from the anchor, so a
 * hexagon only exists once something inside it has been asked about, and empty country costs nothing.
 * <p>
 * <strong>The three constants at the top are the whole design</strong>, and changing one changes every
 * hexagon's id: {@code HexagonRepository} notices at startup and resets the hexagon-keyed tables
 * (hexagons, history, river cells), which is the deal a re-gridding makes.
 * <p>
 * Hexagons, and only hexagons: they are the tiling whose cells are nearest to round — every point in
 * one is within half a width of its centre. Axial coordinates {@code (q, r)}; a hexagon's id is {@code q_r}. The size is the
 * width across the flats, so a 17 km hexagon is 17 km wide and 20 km tall.
 */
public final class Grid {

    /**
     * The width of a hexagon across the flats, in kilometres.
     */
    public static final double CELL_KM = 17;

    /**
     * The anchor: hexagon {@code 0_0} is centred here. The Murray Bridge Golf Course, as OpenStreetMap
     * places it (the course, not the clubhouse 400 m east).
     */
    public static final double ANCHOR_LAT = -35.13133;
    public static final double ANCHOR_LON = 139.26558;

    /**
     * How far outside a hexagon a Bureau station still counts as the hexagon's own, until the console
     * sets another ({@link Reach}, W-18): a quarter of the width, 4.25 km at 17 km. A station a few
     * hundred metres over the line is as much this hexagon's weather as its neighbour's, and without
     * a reach the hexagon beside a town's station had nothing of its own. A station reaches every
     * hexagon whose edge is within the reach - its own and, at this default, up to three neighbours
     * at a corner - and each of them takes it as "in it". Not part of the grid's spec: a change to
     * the reach re-links the hexagons to their stations and resets nothing.
     */
    public static final double DEFAULT_STATION_REACH_KM = CELL_KM / 4;

    private static final double SQRT3 = Math.sqrt(3);

    /** Declared after SQRT3: static fields initialise in order, and the constructor divides by it. */
    public static final Grid DEFAULT = new Grid(CELL_KM);

    private final double cellMetres;
    /** Circumradius: centre to corner. */
    private final double size;
    /** The anchor on the plane, which every centre is offset from. */
    private final double anchorX, anchorY;

    public Grid(double cellKm) {
        if (cellKm <= 0) {
            throw new IllegalArgumentException("a hexagon needs a positive width, not " + cellKm + " km");
        }
        this.cellMetres = cellKm * 1000;
        this.size = cellMetres / SQRT3;
        double[] anchor = Albers.forward(ANCHOR_LAT, ANCHOR_LON);
        this.anchorX = anchor[0];
        this.anchorY = anchor[1];
    }

    public double cellKm() {
        return cellMetres / 1000;
    }

    /**
     * What this grid is, in one string: the width and the anchor. Stored with the data, so a change
     * to either constant is noticed at startup and the data keyed on the old grid is reset.
     */
    public String spec() {
        return "hexagons " + cellKm() + " km, anchored " + ANCHOR_LAT + "," + ANCHOR_LON;
    }

    /**
     * The ground one hexagon covers: width {@code w} across the flats has area {@code w² √3 / 2}.
     */
    public double areaKm2() {
        double km = cellMetres / 1000;
        return km * km * SQRT3 / 2;
    }

    /**
     * The hexagon a point falls in: the plane from the anchor, flat-topped axial from the plane, then
     * cube rounding to the nearest centre.
     */
    public Cell cellOf(double lat, double lon) {
        double[] xy = Albers.forward(lat, lon);
        double x = xy[0] - anchorX, y = xy[1] - anchorY;
        double q = 2.0 / 3.0 * x / size;
        double r = (-1.0 / 3.0 * x + SQRT3 / 3.0 * y) / size;
        int[] qr = roundAxial(q, r);
        return cell(qr[0], qr[1]);
    }

    /**
     * The hexagon at these axial coordinates, with its centre worked out.
     */
    public Cell cell(int q, int r) {
        double[] xy = centre(q, r);
        double[] ll = Albers.inverse(xy[0], xy[1]);
        return new Cell(id(q, r), q, r, ll[0], ll[1]);
    }

    public Cell parse(String id) {
        int cut = id == null ? -1 : id.indexOf('_');
        if (cut <= 0) {
            throw new IllegalArgumentException("not a hexagon id: " + id);
        }
        try {
            return cell(Integer.parseInt(id.substring(0, cut)), Integer.parseInt(id.substring(cut + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a hexagon id: " + id);
        }
    }

    public static String id(int q, int r) {
        return q + "_" + r;
    }

    /**
     * The six hexagons around one.
     */
    public List<Cell> ring(Cell c) {
        List<Cell> out = new ArrayList<>();
        int[][] d = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
        for (int[] v : d) {
            out.add(cell(c.q() + v[0], c.r() + v[1]));
        }
        return out;
    }

    /**
     * The hexagons exactly {@code steps} away: six at one step, twelve at two, {@code 6 × steps} in
     * general. The neighbourhoods the interpolation of "now" walks outwards through.
     */
    public List<Cell> ring(Cell c, int steps) {
        if (steps <= 1) {
            return ring(c);
        }
        List<Cell> out = new ArrayList<>();
        for (int dq = -steps; dq <= steps; dq++) {
            for (int dr = -steps; dr <= steps; dr++) {
                if ((Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2 == steps) {
                    out.add(cell(c.q() + dq, c.r() + dr));
                }
            }
        }
        return out;
    }

    /**
     * The distance from a point to a hexagon, in kilometres: zero inside it, else to its nearest edge.
     */
    public double distanceKm(Cell c, double lat, double lon) {
        double[] p = Albers.forward(lat, lon), o = centre(c.q(), c.r());
        double dx = p[0] - o[0], dy = p[1] - o[1];
        if (inside(dx, dy)) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i), b = Math.toRadians(60 * (i + 1));
            best = Math.min(best, toSegment(dx, dy, size * Math.cos(a), size * Math.sin(a), size * Math.cos(b), size * Math.sin(b)));
        }
        return best / 1000;
    }

    /**
     * The hexagons a point counts for: the one it is in, and any hexagon whose edge is within the
     * reach, ring by ring outwards. At a small reach that is one inside, two near an edge, three
     * near a corner; at a reach past a circumradius (9.8 km at 17 km) the second ring comes into
     * play, since a point on a corner is exactly one edge's length from a second-ring corner.
     * <p>
     * A ring is looked at while any of it could be in reach: its centres are at least {@code n·w·√3/2}
     * from this hexagon's, the point is within a circumradius ({@code w/√3}) of that centre and a
     * hexagon's edge within a circumradius of its own, so a ring whose nearest possible edge is past
     * the reach ends the walk. The first ring is always looked at: a point on a corner reaches two
     * neighbours at a reach of nothing.
     */
    public List<Cell> cellsReaching(double lat, double lon, double reachKm) {
        Cell own = cellOf(lat, lon);
        List<Cell> out = new ArrayList<>();
        out.add(own);
        if (reachKm < 0) {
            return out;
        }
        for (int n = 1; (n * SQRT3 / 2 - 2 / SQRT3) * cellKm() <= reachKm; n++) {
            for (Cell c : ring(own, n)) {
                if (distanceKm(c, lat, lon) <= reachKm) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * The distance from a point to a segment, all in metres on the plane.
     */
    private static double toSegment(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay, wx = px - ax, wy = py - ay;
        double t = Math.max(0, Math.min(1, (wx * vx + wy * vy) / (vx * vx + vy * vy)));
        return Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    /**
     * The distance in metres between two points on the plane: what the interpolation weights by.
     */
    public static double planarMetres(double lat1, double lon1, double lat2, double lon2) {
        double[] a = Albers.forward(lat1, lon1), b = Albers.forward(lat2, lon2);
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /**
     * The hexagon's outline as a closed ring of {@code [lat, lon]} pairs, first vertex repeated last,
     * for GeoJSON and for the raster overlay.
     */
    public List<double[]> outline(Cell c) {
        double[] xy = centre(c.q(), c.r());
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i);
            out.add(Albers.inverse(xy[0] + size * Math.cos(a), xy[1] + size * Math.sin(a)));
        }
        out.add(out.getFirst());
        return out;
    }

    /**
     * Every hexagon whose centre falls inside a lat/lon box, for the console's "show every hexagon"
     * switch. Bounded, because the tessellation of the whole country is tens of thousands of hexagons
     * and a map zoomed out that far cannot draw them anyway.
     *
     * @return the hexagons, or an empty list when the box holds more than {@code max}
     */
    public List<Cell> within(double south, double west, double north, double east, int max) {
        // Walk the box's projected extent with a margin of one cell so the edge cells are included.
        double[] a = Albers.forward(south, west), b = Albers.forward(north, east);
        double[] c = Albers.forward(south, east), d = Albers.forward(north, west);
        double minX = Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])) - cellMetres;
        double maxX = Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0])) + cellMetres;
        double minY = Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1])) - cellMetres;
        double maxY = Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1])) + cellMetres;
        double stepX = size * 1.5;
        double stepY = size * SQRT3 / 2;
        long count = (long) ((maxX - minX) / stepX + 2) * (long) ((maxY - minY) / stepY + 2);
        if (count > max * 4L) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Cell> out = new ArrayList<>();
        for (double y = minY; y <= maxY; y += stepY) {
            for (double x = minX; x <= maxX; x += stepX) {
                double[] ll = Albers.inverse(x, y);
                Cell cell = cellOf(ll[0], ll[1]);
                if (cell.lat() < south || cell.lat() > north || cell.lon() < west || cell.lon() > east) {
                    continue;
                }
                if (seen.add(cell.id())) {
                    out.add(cell);
                    if (out.size() > max) {
                        return List.of();
                    }
                }
            }
        }
        return out;
    }

    /**
     * A lattice of {@code across × across} points spread over the hexagon's bounding box, keeping the
     * ones inside it — for reading a raster across the whole hexagon rather than at its centre
     * (docs/06 items 17 and 19). About three-quarters of the lattice survives.
     */
    public List<double[]> lattice(Cell c, int across) {
        double[] xy = centre(c.q(), c.r());
        double halfW = size;
        double halfH = size * SQRT3 / 2;
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < across; i++) {
            for (int j = 0; j < across; j++) {
                double dx = -halfW + (i + 0.5) * 2 * halfW / across;
                double dy = -halfH + (j + 0.5) * 2 * halfH / across;
                if (inside(dx, dy)) {
                    out.add(Albers.inverse(xy[0] + dx, xy[1] + dy));
                }
            }
        }
        return out;
    }

    /**
     * Whether an offset from a hexagon's centre, in metres on the plane, is inside it. A flat-topped
     * hexagon of circumradius {@code s} spans {@code ±s} horizontally at its middle and narrows by
     * {@code |dy| / √3} as it rises.
     */
    private boolean inside(double dx, double dy) {
        return Math.abs(dy) <= size * SQRT3 / 2 && Math.abs(dx) <= size - Math.abs(dy) / SQRT3;
    }

    /**
     * A hexagon's centre on the plane: the axial offset from the anchor.
     */
    private double[] centre(int q, int r) {
        return new double[]{anchorX + size * 1.5 * q, anchorY + size * SQRT3 * (r + q / 2.0)};
    }

    /**
     * Cube rounding: the fractional axial pair to the nearest hexagon centre.
     */
    private static int[] roundAxial(double q, double r) {
        double s = -q - r;
        long rq = Math.round(q), rr = Math.round(r), rs = Math.round(s);
        double dq = Math.abs(rq - q), dr = Math.abs(rr - r), ds = Math.abs(rs - s);
        if (dq > dr && dq > ds) {
            rq = -rr - rs;
        } else if (dr > ds) {
            rr = -rq - rs;
        }
        return new int[]{(int) rq, (int) rr};
    }
}
