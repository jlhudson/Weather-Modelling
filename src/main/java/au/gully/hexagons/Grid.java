package au.gully.hexagons;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The cell generator (docs/06 item 1). Australia is divided into cells of {@link #CELL_KM} across
 * with {@link #SIDES} sides, and every reading belongs to one. The cells are not stored anywhere: any
 * point's cell is arithmetic on the Albers plane, so a cell only exists once something inside it has
 * been asked about, and empty country costs nothing.
 * <p>
 * <strong>The two constants at the top are the whole design.</strong> Six sides is the default
 * because hexagons are the tiling whose cells are nearest to round — every point in one is within
 * half a cell-width of its centre, where a square's corner is 0.71 of a width away — and because a
 * ring of six is the natural "district" for the drought maths. Four is the other shape that tiles
 * without gaps, kept for an upstream whose model is a square grid. An upstream with a finer or coarser
 * model gets its own instance with its own size; {@link #DEFAULT} is the reading's.
 * <p>
 * Flat-topped hexagons in axial coordinates {@code (q, r)}; a cell's id is {@code q_r}. The size is
 * the width across the flats, so a 15 km hexagon is 15 km wide and 17.3 km tall; a 15 km square is
 * 15 km both ways.
 */
public final class Grid {

    public static final double CELL_KM = 15;
    public static final int SIDES = 6;

    private static final double SQRT3 = Math.sqrt(3);

    /** Declared after SQRT3: static fields initialise in order, and the constructor divides by it. */
    public static final Grid DEFAULT = new Grid(CELL_KM, SIDES);

    private final double cellMetres;
    private final int sides;
    /** Circumradius of a hexagon, or half the side of a square. */
    private final double size;

    public Grid(double cellKm, int sides) {
        if (sides != 6 && sides != 4) {
            throw new IllegalArgumentException("only four- and six-sided cells tile without gaps, not " + sides);
        }
        if (cellKm <= 0) {
            throw new IllegalArgumentException("a cell needs a positive width, not " + cellKm + " km");
        }
        this.cellMetres = cellKm * 1000;
        this.sides = sides;
        this.size = sides == 6 ? cellMetres / SQRT3 : cellMetres / 2;
    }

    public double cellKm() {
        return cellMetres / 1000;
    }

    public int sides() {
        return sides;
    }

    /**
     * The ground one cell covers. A hexagon of width {@code w} across the flats has area
     * {@code w² √3 / 2}; a square {@code w²}.
     */
    public double areaKm2() {
        double km = cellMetres / 1000;
        return sides == 6 ? km * km * SQRT3 / 2 : km * km;
    }

    /**
     * The cell a point falls in.
     */
    public Cell cellOf(double lat, double lon) {
        double[] xy = Albers.forward(lat, lon);
        if (sides == 4) {
            return cell((int) Math.floor(xy[0] / cellMetres), (int) Math.floor(xy[1] / cellMetres));
        }
        // Flat-topped axial from the plane, then cube rounding to the nearest centre.
        double q = 2.0 / 3.0 * xy[0] / size;
        double r = (-1.0 / 3.0 * xy[0] + SQRT3 / 3.0 * xy[1]) / size;
        int[] qr = roundAxial(q, r);
        return cell(qr[0], qr[1]);
    }

    /**
     * The cell at these axial coordinates, with its centre worked out.
     */
    public Cell cell(int q, int r) {
        double[] xy = centre(q, r);
        double[] ll = Albers.inverse(xy[0], xy[1]);
        return new Cell(id(q, r), q, r, ll[0], ll[1]);
    }

    public Cell parse(String id) {
        int cut = id == null ? -1 : id.indexOf('_');
        if (cut <= 0) {
            throw new IllegalArgumentException("not a cell id: " + id);
        }
        try {
            return cell(Integer.parseInt(id.substring(0, cut)), Integer.parseInt(id.substring(cut + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a cell id: " + id);
        }
    }

    public static String id(int q, int r) {
        return q + "_" + r;
    }

    /**
     * The cells around one: six for a hexagon, eight for a square (a square's diagonal neighbours are
     * as close to it as a hexagon's edge neighbours are).
     */
    public List<Cell> ring(Cell c) {
        List<Cell> out = new ArrayList<>();
        if (sides == 6) {
            int[][] d = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
            for (int[] v : d) {
                out.add(cell(c.q() + v[0], c.r() + v[1]));
            }
        } else {
            for (int dq = -1; dq <= 1; dq++) {
                for (int dr = -1; dr <= 1; dr++) {
                    if (dq != 0 || dr != 0) {
                        out.add(cell(c.q() + dq, c.r() + dr));
                    }
                }
            }
        }
        return out;
    }

    /**
     * How many steps apart two cells are: for hexagons the axial distance, for squares the
     * Chebyshev distance (a diagonal step counts one, as {@link #ring} counts it).
     */
    public int distance(Cell a, Cell b) {
        int dq = a.q() - b.q(), dr = a.r() - b.r();
        if (sides == 6) {
            return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
        }
        return Math.max(Math.abs(dq), Math.abs(dr));
    }

    /**
     * The cell and every cell within {@code radius} steps of it: for a hexagon, radius 1 is the cell
     * and its ring (seven), radius 2 is nineteen, and so on ({@code 3r² + 3r + 1}); for a square,
     * the {@code (2r + 1)²} block.
     */
    public List<Cell> area(Cell centre, int radius) {
        List<Cell> out = new ArrayList<>();
        for (int dq = -radius; dq <= radius; dq++) {
            for (int dr = -radius; dr <= radius; dr++) {
                Cell c = cell(centre.q() + dq, centre.r() + dr);
                if (distance(centre, c) <= radius) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * The centre of the area of {@code radius} that a cell belongs to, in a fixed tiling of the
     * plane by such areas: every cell is within {@code radius} of exactly one centre, and the centres
     * never move, so the areas neither overlap nor depend on which cell was asked about first
     * (docs/06 item 7, W-11).
     * <p>
     * For hexagons the centres are the lattice spanned by {@code (r+1, r)} and {@code (-r, 2r+1)} in
     * axial coordinates, whose index is {@code 3r² + 3r + 1} — the size of the area, which is what
     * makes the areas tile exactly (radius 1 is the seven-cell flower). A cell's lattice coordinates
     * are solved for, rounded, and the neighbouring lattice points checked for the one within reach.
     * For squares the centres are the multiples of {@code 2r + 1}.
     */
    public Cell areaCentre(Cell c, int radius) {
        if (radius <= 0) {
            return c;
        }
        if (sides != 6) {
            int side = 2 * radius + 1;
            return cell(Math.floorDiv(c.q() + radius, side) * side, Math.floorDiv(c.r() + radius, side) * side);
        }
        int uq = radius + 1, ur = radius, vq = -radius, vr = 2 * radius + 1;
        double det = (double) uq * vr - (double) vq * ur;
        double a = (c.q() * vr - c.r() * vq) / det;
        double b = (c.r() * uq - c.q() * ur) / det;
        long a0 = Math.round(a), b0 = Math.round(b);
        Cell best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (long da = -1; da <= 1; da++) {
            for (long db = -1; db <= 1; db++) {
                long la = a0 + da, lb = b0 + db;
                Cell centre = cell((int) (la * uq + lb * vq), (int) (la * ur + lb * vr));
                int d = distance(c, centre);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = centre;
                }
            }
        }
        return best;
    }

    /**
     * The cell's outline as a closed ring of {@code [lat, lon]} pairs, first vertex repeated last, for
     * GeoJSON and for the raster overlay.
     */
    public List<double[]> outline(Cell c) {
        double[] xy = centre(c.q(), c.r());
        List<double[]> out = new ArrayList<>();
        if (sides == 6) {
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(60 * i);
                out.add(Albers.inverse(xy[0] + size * Math.cos(a), xy[1] + size * Math.sin(a)));
            }
        } else {
            double h = size;
            out.add(Albers.inverse(xy[0] - h, xy[1] - h));
            out.add(Albers.inverse(xy[0] + h, xy[1] - h));
            out.add(Albers.inverse(xy[0] + h, xy[1] + h));
            out.add(Albers.inverse(xy[0] - h, xy[1] + h));
        }
        out.add(out.getFirst());
        return out;
    }

    /**
     * Every cell whose centre falls inside a lat/lon box, for the console's "show every hexagon"
     * switch. Bounded, because the tessellation of the whole country is forty thousand cells and a map
     * zoomed out that far cannot draw them anyway.
     *
     * @return the cells, or an empty list when the box holds more than {@code max}
     */
    public List<Cell> within(double south, double west, double north, double east, int max) {
        // Walk the box's projected extent with a margin of one cell so the edge cells are included.
        double[] a = Albers.forward(south, west), b = Albers.forward(north, east);
        double[] c = Albers.forward(south, east), d = Albers.forward(north, west);
        double minX = Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])) - cellMetres;
        double maxX = Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0])) + cellMetres;
        double minY = Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1])) - cellMetres;
        double maxY = Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1])) + cellMetres;
        double stepX = sides == 6 ? size * 1.5 : cellMetres;
        double stepY = sides == 6 ? size * SQRT3 / 2 : cellMetres;
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
     * A lattice of {@code across × across} points spread over the cell's bounding box, keeping the
     * ones inside the cell — for reading a raster across the whole cell rather than at its centre
     * (docs/06 items 17 and 19). About three-quarters of the lattice survives on a hexagon.
     */
    public List<double[]> lattice(Cell c, int across) {
        double[] xy = centre(c.q(), c.r());
        double halfW = sides == 6 ? size : size;
        double halfH = sides == 6 ? size * SQRT3 / 2 : size;
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
     * Whether an offset from a cell's centre, in metres on the plane, is inside the cell. A
     * flat-topped hexagon of circumradius {@code s} spans {@code ±s} horizontally at its middle and
     * narrows by {@code |dy| / √3} as it rises.
     */
    private boolean inside(double dx, double dy) {
        if (sides == 4) {
            return Math.abs(dx) <= size && Math.abs(dy) <= size;
        }
        return Math.abs(dy) <= size * SQRT3 / 2 && Math.abs(dx) <= size - Math.abs(dy) / SQRT3;
    }

    private double[] centre(int q, int r) {
        if (sides == 6) {
            return new double[]{size * 1.5 * q, size * SQRT3 * (r + q / 2.0)};
        }
        return new double[]{(q + 0.5) * cellMetres, (r + 0.5) * cellMetres};
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
