package au.gully.reading;

import au.gully.upstreams.OpenMeteo;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The river near a place (W-26): GloFAS's modelled discharge, through Open-Meteo's flood API. GloFAS's cells are
 * 0.05° and the one nearest a point is often beside the channel rather than on it - at Renmark the nearest cell reads
 * nothing and the Murray is the next one west - so the river is the largest flow in the three-by-three block around
 * the point, found once in a single call and remembered; then that cell's last 92 days and week ahead are fetched,
 * held {@link #LIFE}. A block with no flow is no river, which is an answer.
 */
@Slf4j
@Service
public class Rivers {

    public static final double CELL_DEG = 0.05;
    /** Less than this is a creek GloFAS models as dry, not a river. */
    public static final double RIVER_CUMECS = 1.0;
    public static final Duration LIFE = Duration.ofHours(12);

    private final Upstreams upstreams;
    /** Block key to the river cell's {lat, lon}, or an empty array for no river. */
    private final Map<String, double[]> riverCell = new ConcurrentHashMap<>();
    private final Map<String, Series> series = new ConcurrentHashMap<>();

    public Rivers(Upstreams upstreams) {
        this.upstreams = upstreams;
    }

    record Series(Instant fetchedAt, List<OpenMeteo.DischargeRow> rows) {
    }

    /**
     * The river near a place: its flow today against its own 92-day mean, whether it is rising, and its peak in the week
     * ahead; {@code river: false} where there is none; empty where the upstreams could not say.
     */
    public Optional<Map<String, Object>> at(double lat, double lon, Instant now, LocalDate today) {
        double baseLat = snap(lat), baseLon = snap(lon);
        String key = String.format(Locale.ROOT, "%.3f,%.3f", baseLat, baseLon);
        double[] cell = riverCell.get(key);
        if (cell == null) {
            double[] lats = new double[9], lons = new double[9];
            for (int i = 0; i < 9; i++) {
                lats[i] = baseLat + (i / 3 - 1) * CELL_DEG;
                lons[i] = baseLon + (i % 3 - 1) * CELL_DEG;
            }
            Optional<List<double[]>> found = upstreams.dischargeAt(lats, lons, key);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            double[] best = found.get().stream().max(java.util.Comparator.comparingDouble(c -> c[2])).orElse(null);
            cell = best == null || best[2] < RIVER_CUMECS ? new double[0] : new double[]{best[0], best[1]};
            riverCell.put(key, cell);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (cell.length == 0) {
            out.put("river", false);
            out.put("note", "GloFAS models no river within about 5 km");
            return Optional.of(out);
        }
        String cellKey = String.format(Locale.ROOT, "%.3f,%.3f", cell[0], cell[1]);
        Series s = series.get(cellKey);
        if (s == null || Duration.between(s.fetchedAt(), now).compareTo(LIFE) >= 0) {
            Optional<List<OpenMeteo.DischargeRow>> rows = upstreams.discharge(cell[0], cell[1], cellKey);
            if (rows.isPresent()) {
                s = new Series(now, rows.get());
                series.put(cellKey, s);
            } else if (s == null) {
                return Optional.empty();
            }
        }
        out.put("river", true);
        out.put("lat", cell[0]);
        out.put("lon", cell[1]);
        out.putAll(summary(s.rows(), today));
        out.put("fetchedAt", s.fetchedAt().toString());
        out.put("source", "GloFAS via Open-Meteo, modelled discharge");
        return Optional.of(out);
    }

    /**
     * Today's flow, the mean of the days before it, the ratio, the trend over the next three days, and the week's peak.
     */
    static Map<String, Object> summary(List<OpenMeteo.DischargeRow> rows, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        Double now = null;
        double sum = 0;
        int n = 0;
        Double peak = null;
        LocalDate peakOn = null;
        Double inThree = null;
        List<Map<String, Object>> ahead = new ArrayList<>();
        for (OpenMeteo.DischargeRow r : rows) {
            if (r.cumecs() == null) {
                continue;
            }
            if (r.date().isBefore(today)) {
                sum += r.cumecs();
                n++;
            } else {
                if (r.date().equals(today)) {
                    now = r.cumecs();
                }
                if (r.date().equals(today.plusDays(3))) {
                    inThree = r.cumecs();
                }
                if (peak == null || r.cumecs() > peak) {
                    peak = r.cumecs();
                    peakOn = r.date();
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("date", r.date().toString());
                d.put("cumecs", round1(r.cumecs()));
                ahead.add(d);
            }
        }
        Double mean = n == 0 ? null : sum / n;
        m.put("cumecs", now == null ? null : round1(now));
        m.put("meanCumecs", mean == null ? null : round1(mean));
        m.put("meanOverDays", n);
        m.put("ratioToMean", now == null || mean == null || mean <= 0 ? null : Math.round(now / mean * 100) / 100.0);
        m.put("trend", now == null || inThree == null ? null : inThree > now * 1.1 ? "rising" : inThree < now * 0.9 ? "falling" : "steady");
        m.put("peakCumecs", peak == null ? null : round1(peak));
        m.put("peakOn", peakOn == null ? null : peakOn.toString());
        m.put("days", ahead);
        return m;
    }

    /**
     * The centre of the GloFAS cell a value falls in: the cells are 0.05° with centres on the quarter-hundredths.
     */
    static double snap(double v) {
        return Math.round((Math.floor(v / CELL_DEG) * CELL_DEG + CELL_DEG / 2) * 1000) / 1000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
