package au.gully.terrain;

import au.gully.hexagons.Cell;
import au.gully.hexagons.Grid;
import au.gully.science.LandUse;
import au.gully.science.LandUse.LandClass;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static au.gully.science.Numbers.round1;

/**
 * The two rasters on the service's own volume — elevation and land cover — read once per hexagon
 * when it is first created (docs/06 items 17 and 19). No upstream call, no expiry, works offline;
 * a finer file replaces either without changing anything else. Without a file, a hexagon that is
 * asked about reads its elevation from Open-Meteo and its land cover from Digital Earth Australia
 * ({ DeaLandCover}) on the ask, so nothing need be mounted for either to be there.
 * <p>
 * Neither file is committed or downloaded by the service. Geoscience Australia's 9-second DEM (about
 * 250 m, a few hundred megabytes for the country) is the elevation; ABARES' catchment-scale land use
 * or Geoscience Australia's land cover is the land cover. Both are mounted at the paths in
 * {@link TerrainProperties}; when a file is not there, the hexagon has no slope and no land use, and
 * its elevation is whatever the upstream reports for its own model cell.
 * <p>
 * <strong>Land-cover classes are a mapping</strong>, because every product numbers its classes
 * differently. The default is ABARES' catchment-scale land use, by its secondary class; a
 * {@code <file>.classes.properties} beside the raster, with lines like {@code 330-339=cropland},
 * overrides it for any other product.
 */
@Slf4j
@Service
public class Terrain {

    /**
     * Points across a hexagon for the overlay: 24 × 24 is about 430 samples inside a hexagon, which
     * at 250 m posts is every post once and at 50 m every fifth, and costs a millisecond.
     */
    static final int LATTICE = 24;

    private final GeoTiff elevation;
    private final GeoTiff landCover;
    private final Map<Integer, LandClass> classes;
    private final String elevationSource;
    private final String landCoverSource;

    public Terrain(TerrainProperties properties) {
        this.elevation = open(properties.elevation(), "elevation");
        this.landCover = open(properties.landCover(), "land cover");
        this.classes = landCover == null ? Map.of() : classesFor(properties.landCover());
        this.elevationSource = elevation == null ? null : properties.elevation().getFileName().toString();
        this.landCoverSource = landCover == null ? null : properties.landCover().getFileName().toString();
    }

    private static GeoTiff open(Path path, String what) {
        if (path == null) {
            log.info("terrain: no {} file configured", what);
            return null;
        }
        if (!Files.isRegularFile(path)) {
            log.warn("terrain: {} file {} is not there; hexagons will carry no {}", what, path, what);
            return null;
        }
        try {
            GeoTiff tiff = GeoTiff.open(path);
            log.info("terrain: {} from {} ({} x {} cells of about {} m)", what, path.getFileName(),
                    tiff.width(), tiff.height(), Math.round(tiff.cellMetres()));
            return tiff;
        } catch (IOException e) {
            log.warn("terrain: {} file {} cannot be read: {}", what, path, e.getMessage());
            return null;
        }
    }

    public boolean hasElevation() {
        return elevation != null;
    }

    public boolean hasLandCover() {
        return landCover != null;
    }

    public String elevationSource() {
        return elevationSource;
    }

    public String landCoverSource() {
        return landCoverSource;
    }

    /**
     * Ground height at a point, metres, from the mounted file.
     */
    public Optional<Double> elevation(double lat, double lon) {
        if (elevation == null) {
            return Optional.empty();
        }
        double v = elevation.sample(lat, lon);
        return Double.isNaN(v) ? Optional.empty() : Optional.of(round1(v));
    }

    /**
     * The mean slope across a cell, degrees, from the height difference between neighbouring lattice
     * points. What the forest fire model wants when it arrives; what the map colours by until then.
     */
    public Optional<Double> meanSlopeDeg(Grid grid, Cell cell) {
        if (elevation == null) {
            return Optional.empty();
        }
        // A box the width of the cell around its centre, sampled n × n: the corners fall a little
        // outside a hexagon, which for a mean slope is neither here nor there.
        int n = 12;
        double[][] h = new double[n][n];
        double step = grid.cellKm() * 1000 / n;
        double sum = 0;
        int count = 0;
        double[] centre = {cell.lat(), cell.lon()};
        double mPerDegLat = 111_320;
        double mPerDegLon = 111_320 * Math.cos(Math.toRadians(cell.lat()));
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double lat = centre[0] + (j - n / 2.0 + 0.5) * step / mPerDegLat;
                double lon = centre[1] + (i - n / 2.0 + 0.5) * step / mPerDegLon;
                h[i][j] = elevation.sample(lat, lon);
            }
        }
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                if (Double.isNaN(h[i][j]) || Double.isNaN(h[i - 1][j]) || Double.isNaN(h[i + 1][j])
                        || Double.isNaN(h[i][j - 1]) || Double.isNaN(h[i][j + 1])) {
                    continue;
                }
                double dzdx = (h[i + 1][j] - h[i - 1][j]) / (2 * step);
                double dzdy = (h[i][j + 1] - h[i][j - 1]) / (2 * step);
                sum += Math.toDegrees(Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy)));
                count++;
            }
        }
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(round1(sum / count));
    }

    /**
     * The class at a point from the mounted file; empty without one, or outside it.
     */
    public Optional<LandClass> landClassAt(double lat, double lon) {
        if (landCover == null) {
            return Optional.empty();
        }
        double at = landCover.sample(lat, lon);
        return Double.isNaN(at) ? Optional.empty() : Optional.of(classOf((int) Math.round(at)));
    }

    /**
     * The land use of a cell as a percentage per class from the mounted file. Empty when there is no
     * file, or the cell is entirely outside it.
     */
    public Optional<LandUse> landUse(Grid grid, Cell cell) {
        if (landCover == null) {
            return Optional.empty();
        }
        Map<LandClass, Integer> counts = new EnumMap<>(LandClass.class);
        int total = 0;
        for (double[] p : grid.lattice(cell, LATTICE)) {
            double v = landCover.sample(p[0], p[1]);
            if (Double.isNaN(v)) {
                continue;
            }
            counts.merge(classOf((int) Math.round(v)), 1, Integer::sum);
            total++;
        }
        if (total == 0) {
            return Optional.empty();
        }
        Map<LandClass, Integer> percent = new EnumMap<>(LandClass.class);
        int t = total;
        counts.forEach((k, v) -> percent.put(k, (int) Math.round(100.0 * v / t)));
        return Optional.of(new LandUse(percent, landCoverSource));
    }

    LandClass classOf(int value) {
        LandClass mapped = classes.get(value);
        return mapped == null ? LandClass.UNKNOWN : mapped;
    }

    /**
     * The value-to-class table: the properties file beside the raster when there is one, else the
     * ABARES catchment-scale land use secondary classes.
     */
    static Map<Integer, LandClass> classesFor(Path raster) {
        Path sidecar = raster.resolveSibling(raster.getFileName() + ".classes.properties");
        if (Files.isRegularFile(sidecar)) {
            try {
                Properties p = new Properties();
                p.load(Files.newBufferedReader(sidecar));
                Map<Integer, LandClass> out = new HashMap<>();
                for (String key : p.stringPropertyNames()) {
                    LandClass c = LandClass.valueOf(p.getProperty(key).trim().toUpperCase().replace('-', '_'));
                    String k = key.trim();
                    int dash = k.indexOf('-');
                    int from = Integer.parseInt(dash < 0 ? k : k.substring(0, dash));
                    int to = dash < 0 ? from : Integer.parseInt(k.substring(dash + 1));
                    for (int v = from; v <= to; v++) {
                        out.put(v, c);
                    }
                }
                log.info("terrain: land-cover classes from {} ({} values)", sidecar.getFileName(), out.size());
                return out;
            } catch (IOException | RuntimeException e) {
                log.warn("terrain: {} cannot be read ({}); using the ABARES classes", sidecar, e.getMessage());
            }
        }
        return abaresClasses();
    }

    /**
     * ABARES Catchment Scale Land Use of Australia, by secondary class (the first two digits of the
     * tertiary code the raster carries). Conservation and native forest are trees; pasture and
     * native grazing are grass; cropping and horticulture are crop; the intensive uses are built-up;
     * mining and land in transition are bare; the water classes are water.
     */
    static Map<Integer, LandClass> abaresClasses() {
        Map<Integer, LandClass> out = new HashMap<>();
        for (int v = 100; v < 700; v++) {
            int secondary = v / 10;
            LandClass c = switch (secondary) {
                case 11, 12 -> LandClass.FOREST;          // nature conservation, managed resource protection
                case 13, 14 -> LandClass.SCRUB;           // other minimal use, and the rest of 1.x
                case 21 -> LandClass.GRASSLAND;           // grazing native vegetation
                case 22 -> LandClass.FOREST;              // production native forests
                case 31, 41 -> LandClass.FOREST;          // plantation forests, irrigated plantations
                case 32, 42 -> LandClass.GRASSLAND;       // grazing modified pastures
                case 33, 34, 35, 43, 44, 45 -> LandClass.CROPLAND;
                case 36, 46, 58 -> LandClass.BARE;        // land in transition, mining
                case 51, 52, 53, 54, 55, 56, 57, 59 -> LandClass.BUILT_UP;
                case 61, 62, 63, 64, 65, 66 -> LandClass.WATER;
                default -> v >= 100 && v < 200 ? LandClass.SCRUB : LandClass.UNKNOWN;
            };
            out.put(v, c);
        }
        return out;
    }

    @PreDestroy
    void close() {
        for (GeoTiff t : new GeoTiff[]{elevation, landCover}) {
            if (t != null) {
                try {
                    t.close();
                } catch (IOException ignored) {
                    // nothing to do on the way out
                }
            }
        }
    }
}
