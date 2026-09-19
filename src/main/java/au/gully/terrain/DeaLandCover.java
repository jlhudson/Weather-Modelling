package au.gully.terrain;

import au.gully.hexagons.Cell;
import au.gully.hexagons.Grid;
import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.UpstreamException;
import au.gully.science.LandUse;
import au.gully.science.LandUse.LandClass;
import au.gully.upstreams.Ledger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Digital Earth Australia's land cover (Geoscience Australia, from Landsat at 30 m, one map per
 * calendar year), read on request for a hexagon that has none (W-15): one WCS {@code GetCoverage}
 * for the hexagon's box, answered as a small GeoTIFF of the product's level-4 class codes, which is
 * kept with the hexagon so the class at any point later asked about is read off it, not fetched
 * again. Nothing is mounted, nothing is downloaded for the country, and a hexagon nobody asks about
 * costs nothing.
 * <p>
 * The level-4 code says what the ground is, whether it is woody or herbaceous, and how closed the
 * canopy is, which is exactly the distinction two fire indices need: trees closed or open are
 * {@code forest}, trees sparse or scattered are {@code scrub}, natural herbaceous cover is
 * {@code grassland}, cultivated herbaceous cover is {@code cropland}, the artificial surface is
 * {@code built_up}, the natural bare surface is {@code bare}, and water is {@code water}. Wetland
 * vegetation follows its lifeform - a paperbark swamp is forest, a reed bed grassland - because both
 * burn when they dry.
 * <p>
 * Each year's map is published the year after; the latest is looked for from last year back
 * {@link #YEARS_BACK}, and the year found is remembered for the next hexagon.
 */
@Slf4j
@Component
public class DeaLandCover {

    public static final String ID = "dea-landcover";
    public static final URI ENDPOINT = URI.create("https://ows.dea.ga.gov.au/wcs");
    public static final String COVERAGE = "ga_ls_landcover_c3";
    public static final String MEASUREMENT = "level4";

    /**
     * Pixels across the hexagon's box: 96 across 17-odd kilometres is a pixel every 180 m or so from
     * a 30 m source, four kilobytes compressed, and enough to count a paddock.
     */
    public static final int PIXELS = 96;

    /**
     * Points across the hexagon counted for the shares: 48 × 48 is about 1,700 inside the outline.
     */
    public static final int LATTICE = 48;

    /**
     * How far back from last year the latest map is looked for.
     */
    static final int YEARS_BACK = 3;

    private final HttpFetcher http;
    private final Ledger ledger;
    private volatile Integer yearFound;

    public DeaLandCover(HttpFetcher http, Ledger ledger) {
        this.http = http;
        this.ledger = ledger;
    }

    /**
     * One hexagon's raster: the bytes of the GeoTIFF and the year it maps.
     */
    public record Cover(byte[] tiff, int year) {

        public String source() {
            return ID + "-" + year;
        }
    }

    /**
     * The raster for a hexagon, or empty when the service could not answer. One call when the year is
     * known; a miss on a year not yet published is tried once and the year before it taken.
     */
    public Optional<Cover> fetch(Grid grid, Cell cell, String why) {
        double south = 90, north = -90, west = 180, east = -180;
        for (double[] v : grid.outline(cell)) {
            south = Math.min(south, v[0]);
            north = Math.max(north, v[0]);
            west = Math.min(west, v[1]);
            east = Math.max(east, v[1]);
        }
        int thisYear = LocalDate.now(ZoneOffset.UTC).getYear();
        int first = yearFound != null ? yearFound : thisYear - 1;
        int last = yearFound != null ? yearFound : thisYear - 1 - YEARS_BACK;
        for (int year = first; year >= last; year--) {
            URI uri = URI.create(String.format(Locale.ROOT,
                    "%s?service=WCS&version=1.0.0&request=GetCoverage&coverage=%s&crs=EPSG:4326&bbox=%.5f,%.5f,%.5f,%.5f"
                            + "&width=%d&height=%d&format=GeoTIFF&time=%d-01-01&measurements=%s",
                    ENDPOINT, COVERAGE, west, south, east, north, PIXELS, PIXELS, year, MEASUREMENT));
            long started = System.nanoTime();
            try {
                Fetched f = http.get(uri);
                Duration took = Duration.ofNanos(System.nanoTime() - started);
                if (f.ok() && f.body() != null && f.body().length > 8 && (f.body()[0] == 'I' || f.body()[0] == 'M')) {
                    yearFound = year;
                    ledger.record(ID, 0, true, took, "land cover " + year + " for " + why + ": " + f.body().length + " bytes");
                    return Optional.of(new Cover(f.body(), year));
                }
                ledger.record(ID, 0, false, took, "land cover " + year + " for " + why + ": status " + f.status());
                log.debug("dea land cover {} for {}: status {} ({})", year, why, f.status(), f.contentType());
            } catch (UpstreamException e) {
                ledger.record(ID, 0, false, Duration.ofNanos(System.nanoTime() - started), "land cover " + year + " for " + why + ": " + e.getMessage());
                // A year not yet published is a 400 from the service: the year before is tried. Anything else is the service.
                if (e.status() == 400 || e.status() == 404) {
                    log.debug("dea land cover {} for {}: not there ({})", year, why, e.getMessage());
                    continue;
                }
                log.warn("dea land cover {} for {}: {}", year, why, e.getMessage());
                return Optional.empty();
            } catch (RuntimeException e) {
                ledger.record(ID, 0, false, Duration.ofNanos(System.nanoTime() - started), "land cover " + year + " for " + why + ": " + e.getMessage());
                log.warn("dea land cover {} for {}: {}", year, why, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * The shares of a hexagon from its raster.
     */
    public static Optional<LandUse> landUse(Cover cover, Grid grid, Cell cell) {
        try (GeoTiff raster = GeoTiff.of(cover.tiff(), cover.source() + ".tif")) {
            Map<LandClass, Integer> counts = new EnumMap<>(LandClass.class);
            int total = 0;
            for (double[] p : grid.lattice(cell, LATTICE)) {
                double v = raster.sample(p[0], p[1]);
                if (Double.isNaN(v) || v == 0 || v == 255) {
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
            return Optional.of(new LandUse(percent, cover.source()));
        } catch (IOException | RuntimeException e) {
            log.warn("dea land cover for {}: raster unreadable: {}", cell.id(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The class at one point, from a hexagon's raster.
     */
    public static Optional<LandClass> classAt(byte[] tiff, double lat, double lon) {
        try (GeoTiff raster = GeoTiff.of(tiff, "land-cover.tif")) {
            double v = raster.sample(lat, lon);
            if (Double.isNaN(v) || v == 0 || v == 255) {
                return Optional.empty();
            }
            return Optional.of(classOf((int) Math.round(v)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * DEA Land Cover level 4 to the seven classes. The codes run in blocks of eighteen per base
     * class - unspecified, woody, herbaceous, five cover bands, five woody bands, five herbaceous
     * bands - with the aquatic classes carrying water-persistence variants after that.
     */
    static LandClass classOf(int code) {
        if (code >= 1 && code <= 18) {
            // Cultivated terrestrial vegetated: plantations and orchards are trees, the rest is crop.
            return switch (code) {
                case 2, 9, 10, 11 -> LandClass.FOREST;    // woody; woody closed and open
                case 12, 13 -> LandClass.SCRUB;           // woody sparse and scattered (vines, young plantings)
                default -> LandClass.CROPLAND;
            };
        }
        if (code >= 19 && code <= 36) {
            // Natural terrestrial vegetated.
            return switch (code) {
                case 20, 27, 28, 29 -> LandClass.FOREST;  // woody; woody closed and open
                case 30, 31 -> LandClass.SCRUB;           // woody sparse and scattered
                case 21, 32, 33, 34, 35, 36 -> LandClass.GRASSLAND;
                case 22, 23, 24 -> LandClass.FOREST;      // lifeform unread but closed or open cover
                default -> LandClass.SCRUB;               // 19, 25, 26: lifeform unread, thin cover
            };
        }
        if (code >= 37 && code <= 54) {
            return LandClass.CROPLAND;                    // cultivated aquatic: rice, irrigated crops
        }
        if (code >= 55 && code <= 92) {
            // Natural aquatic vegetated: woody swamps are forest, reed beds grassland, by the lifeform.
            if (code == 56 || (code >= 63 && code <= 71)) {
                return LandClass.FOREST;
            }
            if (code >= 72 && code <= 77) {
                return LandClass.SCRUB;
            }
            if (code == 57 || code >= 78) {
                return LandClass.GRASSLAND;
            }
            return LandClass.SCRUB;                       // 55, 58 to 62: lifeform unread
        }
        if (code == 93) {
            return LandClass.BUILT_UP;
        }
        if (code >= 94 && code <= 97) {
            return LandClass.BARE;
        }
        if (code >= 98 && code <= 104) {
            return LandClass.WATER;
        }
        return LandClass.UNKNOWN;
    }
}
