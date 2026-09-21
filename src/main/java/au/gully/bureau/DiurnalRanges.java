package au.gully.bureau;

import au.gully.storage.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A station's diurnal temperature range (W-25): how far the day climbs from the morning. A day's
 * range is the Bureau's pairing - the highest reading from 9 am local against the lowest in the
 * 24 hours to that 9 am - so the figure compares with the Bureau's published daily maximum and
 * minimum. The highs and lows come from the six-hourly ledger's windows (W-19), each folded into
 * the Bureau day (9 am to 9 am, W-21) it lies in - a window across 9 am rises through it, so its
 * high is the day's that starts there and its low the morning's of the day that ends there; a row
 * without a window (from before W-19, or the first after a restart) stands for the spot reading it
 * holds. A day is complete when its own 24 hours and the 24 hours before it each drew on
 * {@link #COMPLETE_ROWS} windows; the week and the month are the mean of their complete days'
 * ranges, and say how many that is.
 * <p>
 * Derived from the record on demand and memoised ten minutes; nothing is kept. Only a hexagon with
 * a station has one - the range is the ground's, never the model's.
 */
@Component
public class DiurnalRanges {

    /**
     * How long an answer stands before the ledger is read again: the ledger changes six-hourly.
     */
    static final Duration MEMO = Duration.ofMinutes(10);
    static final int WEEK = 7;
    static final int MONTH = 30;
    /**
     * Windowed ledger rows a 24-hour span needs before it counts: three of its four.
     */
    static final int COMPLETE_ROWS = 3;
    /**
     * The Bureau's day starts at 9 am local (W-21).
     */
    static final LocalTime DAY_STARTS = LocalTime.of(9, 0);

    private final JdbcClient db;
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();

    public DiurnalRanges(JdbcClient db) {
        this.db = db;
    }

    /**
     * The station's ranges now, or empty where the ledger holds nothing for it yet.
     */
    public Optional<Diurnal> of(Station s) {
        Instant now = Instant.now();
        Memo m = memo.get(s.id());
        if (m != null && Duration.between(m.at(), now).compareTo(MEMO) < 0) {
            return Optional.ofNullable(m.value());
        }
        ZoneId zone = StationRegistry.zoneOf(s);
        LocalDate today = bureauDay(now.atZone(zone));
        Instant since = today.minusDays(MONTH + 1).atTime(DAY_STARTS).atZone(zone).toInstant();
        List<Row> rows = new ArrayList<>();
        db.sql("select at, temperature_c, temp_min_c, temp_max_c, readings from station_sample"
                        + " where station_id = :id and at >= :since order by at")
                .param("id", s.id()).param("since", Db.ts(since)).query().listOfRows()
                .forEach(r -> {
                    Instant at = Db.instant(r.get("at"));
                    if (at != null) {
                        rows.add(new Row(at, Db.dbl(r.get("temperature_c")), Db.dbl(r.get("temp_min_c")),
                                Db.dbl(r.get("temp_max_c")), Db.integer(r.get("readings"))));
                    }
                });
        Diurnal d = fold(rows, zone, now);
        memo.put(s.id(), new Memo(now, d));
        return Optional.ofNullable(d);
    }

    /**
     * The ledger's rows for one station folded into Bureau days and paired the Bureau's way; null
     * where there is not a single reading.
     */
    static Diurnal fold(List<Row> rows, ZoneId zone, Instant now) {
        if (rows.isEmpty()) {
            return null;
        }
        Map<LocalDate, Fold> folds = new HashMap<>();
        for (Row r : rows) {
            boolean windowed = r.readings() != null && r.readings() > 0;
            ZonedDateTime end = r.at().atZone(zone);
            if (!windowed) {
                // A bare row is the spot reading at its moment: a candidate for either extreme, counted for neither.
                folds.computeIfAbsent(bureauDay(end), d -> new Fold()).spot(r.t());
                continue;
            }
            // A window across 9 am rises through it: its high is the day's that starts there, its low
            // the morning's of the day that ends there. One that does not cross gives both to its day.
            LocalDate dayAtEnd = bureauDay(end);
            LocalDate dayAtStart = bureauDay(end.minus(StationRegistry.LEDGER_EVERY));
            folds.computeIfAbsent(dayAtEnd, d -> new Fold()).high(r.tMax() != null ? r.tMax() : r.t());
            folds.computeIfAbsent(dayAtStart, d -> new Fold()).low(r.tMin() != null ? r.tMin() : r.t());
        }
        LocalDate today = bureauDay(now.atZone(zone));
        Day last = null;
        List<Day> week = new ArrayList<>();
        List<Day> month = new ArrayList<>();
        for (LocalDate d = today.minusDays(MONTH); d.isBefore(today); d = d.plusDays(1)) {
            Day day = day(d, folds, false);
            if (day == null) {
                continue;
            }
            if (day.complete()) {
                last = day;
                month.add(day);
                if (!d.isBefore(today.minusDays(WEEK))) {
                    week.add(day);
                }
            }
        }
        return new Diurnal(last, day(today, folds, true), period(week, WEEK), period(month, MONTH));
    }

    /**
     * One Bureau day: its own 24 hours' high against the 24 hours before's low. Null with neither.
     */
    private static Day day(LocalDate d, Map<LocalDate, Fold> folds, boolean soFar) {
        Fold own = folds.get(d);
        Fold before = folds.get(d.minusDays(1));
        Double high = own == null ? null : own.high;
        Double low = before == null ? null : before.low;
        if (high == null && low == null) {
            return null;
        }
        boolean complete = !soFar && own != null && before != null && own.highs >= COMPLETE_ROWS && before.lows >= COMPLETE_ROWS;
        Double range = high == null || low == null ? null : round1(high - low);
        return new Day(d, high, low, range, complete);
    }

    private static Period period(List<Day> complete, int of) {
        double sum = 0;
        int n = 0;
        for (Day d : complete) {
            if (d.rangeC() != null) {
                sum += d.rangeC();
                n++;
            }
        }
        return new Period(n == 0 ? null : round1(sum / n), n, of);
    }

    /**
     * The Bureau day a local moment falls in: the date it started on, at 9 am.
     */
    static LocalDate bureauDay(ZonedDateTime local) {
        return local.toLocalTime().isBefore(DAY_STARTS) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /**
     * A ledger row as the fold reads it: the spot reading, the window's extremes and how many
     * readings the window consolidated (null before W-19).
     */
    record Row(Instant at, Double t, Double tMin, Double tMax, Integer readings) {
    }

    /**
     * One Bureau day's extremes as they gather, and how many windows each came from.
     */
    private static final class Fold {
        Double high;
        Double low;
        int highs;
        int lows;

        void high(Double h) {
            if (h != null) {
                high = high == null ? h : Math.max(high, h);
                highs++;
            }
        }

        void low(Double l) {
            if (l != null) {
                low = low == null ? l : Math.min(low, l);
                lows++;
            }
        }

        void spot(Double t) {
            if (t != null) {
                high = high == null ? t : Math.max(high, t);
                low = low == null ? t : Math.min(low, t);
            }
        }
    }

    private record Memo(Instant at, Diurnal value) {
    }

    /**
     * One Bureau day's range: the high from 9 am on, the low in the 24 hours to that 9 am, and
     * whether both spans are complete - today never is, and says so.
     */
    public record Day(LocalDate date, Double highC, Double lowC, Double rangeC, boolean complete) {
    }

    /**
     * The mean daily range over the last {@code of} days, from the {@code days} complete ones.
     */
    public record Period(Double meanRangeC, int days, int of) {
    }

    /**
     * @param day   the last complete day, or null before there is one
     * @param today the current Bureau day so far, or null before it has a reading
     */
    public record Diurnal(Day day, Day today, Period week, Period month) {
    }
}
