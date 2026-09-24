package au.gully.record;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.storage.Db;
import au.gully.upstreams.OpenMeteo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * What a station's readings are kept as once the file has moved on (W-6, W-15). Once a day the
 * housekeeping folds each Bureau station's stored readings into the Bureau day that ended at the
 * last 9 am: four six-hour {@link Window}s ending 3 pm, 9 pm, 3 am and 9 am local, written to
 * {@code station_hour6}, and the day itself, written to {@code station_day}. The day's rain is the
 * total to 9 am that the first reading at or after 9 am publishes; its maximum the highest of the
 * day's windows and of the running maximum the Bureau published by 9 pm (6 am to 9 pm). A day is written
 * only when it has a rain figure and a reading in each of its four windows; one without is left
 * absent for the archive to fill, whose maximum is the whole day's where a part-day's is not. The
 * fold is idempotent, and the housekeeping folds the last three days lacking their own row, so a
 * run that was missed is caught up by the next.
 * <p>
 * The days are held in memory for every station, the last {@link #KEEP}: it is what the drought
 * integrates over, on demand, and it is small - eighty stations at five hundred days.
 */
@Slf4j
@Service
public class Record {

    /**
     * How long the record is kept: a year's spin-up and a half again.
     */
    public static final Duration KEEP = Duration.ofDays(548);
    /**
     * How much record the drought wants behind it.
     */
    public static final int SPIN_UP_DAYS = 365;
    /**
     * How many days back the housekeeping folds: the readings are kept as long.
     */
    public static final int FOLD_DAYS = 3;
    /**
     * The windows end on these local hours; the day turns on the second.
     */
    static final int[] BOUNDARY_HOURS = {3, 9, 15, 21};
    static final int WINDOWS_PER_DAY = BOUNDARY_HOURS.length;
    public static final LocalTime DAY_TURNS_AT = LocalTime.of(9, 0);

    public static final String SOURCE_BUREAU = "bureau";
    public static final String SOURCE_ARCHIVE = "archive";

    private final JdbcClient db;
    private final StationRegistry stations;
    private final Map<String, NavigableMap<LocalDate, Day>> days = new ConcurrentHashMap<>();

    public Record(JdbcClient db, StationRegistry stations) {
        this.db = db;
        this.stations = stations;
    }

    /**
     * One day of a station's record.
     */
    public record Day(LocalDate day, Double rainMm, Double maxTempC, String source) {
    }

    /**
     * One closed window, as written.
     */
    public record Hour6(Instant at, int readings, Double tMin, Double tMax, Double tMean, Integer rhMin, Integer rhMax,
                        Double wMean, Double wMax, Double gMax, Double rainSince9am, Double rain24h, Double publishedMax) {
    }

    /**
     * What a fold of one day made: its windows, and the day if it could be written.
     */
    public record Folded(List<Hour6> windows, Optional<Day> day) {
    }

    // ---------------------------------------------------------------- the start

    public void rehydrate() {
        days.clear();
        Instant since = Instant.now().minus(KEEP);
        db.sql("select station_id, day, rain_mm, max_temp_c, source from station_day where day >= :since order by day")
                .param("since", LocalDate.ofInstant(since, ZoneId.of("UTC"))).query().listOfRows().forEach(row ->
                        daysOf((String) row.get("station_id")).put(Db.date(row.get("day")),
                                new Day(Db.date(row.get("day")), Db.dbl(row.get("rain_mm")), Db.dbl(row.get("max_temp_c")), (String) row.get("source"))));
        long rows = days.values().stream().mapToLong(Map::size).sum();
        log.info("record rehydrated: {} days over {} stations", rows, days.size());
    }

    private NavigableMap<LocalDate, Day> daysOf(String stationId) {
        return days.computeIfAbsent(stationId, k -> new ConcurrentSkipListMap<>());
    }

    // ---------------------------------------------------------------- the fold

    /**
     * The housekeeping's fold: every Bureau station's last {@link #FOLD_DAYS} days that lack a row of
     * the station's own, folded from its stored readings.
     *
     * @return how many days were written
     */
    public int fold(Instant now) {
        int written = 0;
        for (Station s : stations.bureau()) {
            ZoneId zone = zoneOf(s);
            LocalDate today = dayOf(now, zone);
            for (int back = FOLD_DAYS; back >= 1; back--) {
                LocalDate day = today.minusDays(back);
                Day held = daysOf(s.id()).get(day);
                if (held != null && SOURCE_BUREAU.equals(held.source())) {
                    continue;
                }
                Instant from = day.atTime(DAY_TURNS_AT).atZone(zone).toInstant();
                Instant to = day.plusDays(1).atTime(DAY_TURNS_AT).atZone(zone).toInstant();
                // The day's readings, and the first hour of the next day's: the 9 am reading carries the total.
                List<Observation> readings = stations.readings(s.id(), from, to.plus(Duration.ofHours(1)));
                if (readings.isEmpty()) {
                    continue;
                }
                Folded f = fold(day, zone, readings);
                for (Hour6 h : f.windows()) {
                    writeWindow(s.id(), h);
                }
                if (f.day().isPresent()) {
                    put(s.id(), f.day().get(), true);
                    written++;
                }
            }
        }
        return written;
    }

    /**
     * One Bureau day of one station, folded from its readings: the four windows from the readings
     * inside the day, and the day from the windows and the reading at or after 9 am that closes it.
     */
    public static Folded fold(LocalDate day, ZoneId zone, List<Observation> readings) {
        Instant from = day.atTime(DAY_TURNS_AT).atZone(zone).toInstant();
        Instant to = day.plusDays(1).atTime(DAY_TURNS_AT).atZone(zone).toInstant();
        Map<Instant, Window> windows = new TreeMap<>();
        Observation closing = null;
        for (Observation o : readings) {
            if (o.at() == null || o.at().isBefore(from)) {
                continue;
            }
            if (!o.at().isBefore(to)) {
                if (closing == null) {
                    closing = o;
                }
                continue;
            }
            windows.computeIfAbsent(boundaryAfter(o.at(), zone), Window::new).add(o);
        }
        // The Bureau's running maximum runs 6 am to 9 pm: the last of it, in the window closing at 9 pm, holds the
        // afternoon's peak even where it fell between two readings.
        Instant evening = day.atTime(21, 0).atZone(zone).toInstant();
        List<Hour6> out = new ArrayList<>();
        Double max = null;
        for (Window w : windows.values()) {
            Hour6 h = hour6(w);
            out.add(h);
            max = maxOf(max, h.tMax());
            if (h.at().equals(evening)) {
                max = maxOf(max, h.publishedMax());
            }
        }
        Double rain = closing == null ? null : closing.rain24hMm();
        // A day is the station's own only when every one of its four windows has a reading: a maximum taken from part
        // of a day - a restart at dusk, a station silent till evening - is not the day's maximum, and the archive's is.
        Optional<Day> d = rain == null || max == null || out.size() < WINDOWS_PER_DAY ? Optional.empty() : Optional.of(new Day(day, rain, max, SOURCE_BUREAU));
        return new Folded(out, d);
    }

    /**
     * The next 3 am, 9 am, 3 pm or 9 pm local strictly after an instant: a window is [start, end),
     * so the 9 am reading - the first of the new day, carrying its total - opens the new window.
     */
    static Instant boundaryAfter(Instant at, ZoneId zone) {
        ZonedDateTime local = at.atZone(zone);
        for (int h : BOUNDARY_HOURS) {
            ZonedDateTime b = local.toLocalDate().atTime(h, 0).atZone(zone);
            if (b.isAfter(local)) {
                return b.toInstant();
            }
        }
        return local.toLocalDate().plusDays(1).atTime(BOUNDARY_HOURS[0], 0).atZone(zone).toInstant();
    }

    static Hour6 hour6(Window w) {
        return new Hour6(w.end, w.readings, w.tMin, w.tMax, w.tMean(), w.rhMin, w.rhMax, w.wMean(), w.wMax, w.gMax, w.rainSince9am, w.rain24h, w.publishedMax);
    }

    private static Double maxOf(Double a, Double b) {
        return a == null ? b : b == null ? a : Double.valueOf(Math.max(a, b));
    }

    /**
     * A window, written; folded again, it is the same row.
     */
    void writeWindow(String stationId, Hour6 h) {
        db.sql("""
                insert into station_hour6 (station_id, at, readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct,
                  wind_mean_kmh, wind_max_kmh, gust_max_kmh, rain_since_9am_mm, rain_24h_mm, published_max_c)
                values (:id, :at, :n, :tmin, :tmax, :tmean, :rhmin, :rhmax, :wmean, :wmax, :gmax, :rain, :rain24, :pmax)
                on conflict (station_id, at) do update set readings = excluded.readings, temp_min_c = excluded.temp_min_c,
                  temp_max_c = excluded.temp_max_c, temp_mean_c = excluded.temp_mean_c, rh_min_pct = excluded.rh_min_pct, rh_max_pct = excluded.rh_max_pct,
                  wind_mean_kmh = excluded.wind_mean_kmh, wind_max_kmh = excluded.wind_max_kmh, gust_max_kmh = excluded.gust_max_kmh,
                  rain_since_9am_mm = excluded.rain_since_9am_mm, rain_24h_mm = excluded.rain_24h_mm, published_max_c = excluded.published_max_c""")
                .param("id", stationId).param("at", Db.ts(h.at())).param("n", h.readings()).param("tmin", h.tMin()).param("tmax", h.tMax())
                .param("tmean", h.tMean()).param("rhmin", h.rhMin()).param("rhmax", h.rhMax()).param("wmean", h.wMean()).param("wmax", h.wMax())
                .param("gmax", h.gMax()).param("rain", h.rainSince9am()).param("rain24", h.rain24h()).param("pmax", h.publishedMax()).update();
    }

    /**
     * One day into the record: the station's own always, the archive's only where there is none.
     */
    public void put(String stationId, Day day, boolean replace) {
        db.sql("insert into station_day (station_id, day, rain_mm, max_temp_c, source, written_at) values (:id, :day, :rain, :max, :source, :now)"
                        + (replace ? " on conflict (station_id, day) do update set rain_mm = excluded.rain_mm, max_temp_c = excluded.max_temp_c, source = excluded.source, written_at = excluded.written_at"
                        : " on conflict (station_id, day) do nothing"))
                .param("id", stationId).param("day", day.day()).param("rain", day.rainMm()).param("max", day.maxTempC())
                .param("source", day.source()).param("now", Db.ts(Instant.now())).update();
        putInMemory(stationId, day, replace);
    }

    void putInMemory(String stationId, Day day, boolean replace) {
        NavigableMap<LocalDate, Day> d = daysOf(stationId);
        if (replace || !d.containsKey(day.day())) {
            d.put(day.day(), day);
        }
    }

    /**
     * The archive's days for a station: each kept only where the station has none of its own.
     *
     * @return how many were new
     */
    public int fill(String stationId, List<OpenMeteo.DailyRow> rows) {
        int added = 0;
        NavigableMap<LocalDate, Day> d = daysOf(stationId);
        for (OpenMeteo.DailyRow r : rows) {
            if (!d.containsKey(r.date())) {
                put(stationId, new Day(r.date(), r.rainMm(), r.maxTemperatureC(), SOURCE_ARCHIVE), false);
                added++;
            }
        }
        return added;
    }

    // ---------------------------------------------------------------- reads

    /**
     * A station's days, oldest first, as held.
     */
    public NavigableMap<LocalDate, Day> days(String stationId) {
        NavigableMap<LocalDate, Day> d = days.get(stationId);
        return d == null ? new TreeMap<>() : d;
    }

    /**
     * The days a station has none of in a range, oldest first.
     */
    public List<LocalDate> missing(String stationId, LocalDate from, LocalDate to) {
        NavigableMap<LocalDate, Day> d = days(stationId);
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!d.containsKey(day)) {
                out.add(day);
            }
        }
        return out;
    }

    /**
     * Today's rain so far, for the drought factor's last day: what the station's latest reading
     * says since 9 am, if that reading is today's.
     */
    public Double rainSoFar(Station s, Instant now) {
        Observation o = stations == null ? null : stations.latest(s.id()).orElse(null);
        if (o == null || o.at() == null) {
            return null;
        }
        ZoneId zone = zoneOf(s);
        return dayOf(o.at(), zone).equals(dayOf(now, zone)) ? o.rainSince9amMm() : null;
    }

    /**
     * The last windows, newest first, for the console.
     */
    public List<Map<String, Object>> recentWindows(String stationId, int limit) {
        return db.sql("select at, readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct, wind_mean_kmh, wind_max_kmh, gust_max_kmh,"
                        + " rain_since_9am_mm, rain_24h_mm, published_max_c from station_hour6 where station_id = :id order by at desc limit :n")
                .param("id", stationId).param("n", Math.max(1, Math.min(limit, 200))).query().listOfRows();
    }

    /**
     * The six-hour window a moment fell in, for a station: the first window ending after it (W-27).
     */
    public Optional<Map<String, Object>> windowAt(String stationId, Instant at) {
        return db.sql("select at, readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct, wind_mean_kmh, wind_max_kmh, gust_max_kmh,"
                        + " rain_since_9am_mm, rain_24h_mm, published_max_c from station_hour6 where station_id = :id and at > :at and at <= :until order by at limit 1")
                .param("id", stationId).param("at", Db.ts(at)).param("until", Db.ts(at.plus(Duration.ofHours(6)))).query().listOfRows().stream().findFirst();
    }

    public long windowRows() {
        Long n = db.sql("select count(*) from station_hour6").query(Long.class).single();
        return n == null ? 0 : n;
    }

    public long dayRows() {
        return days.values().stream().mapToLong(Map::size).sum();
    }

    /**
     * Rows older than {@link #KEEP}, gone.
     */
    public int prune(Instant now) {
        LocalDate before = LocalDate.ofInstant(now.minus(KEEP), ZoneId.of("UTC"));
        int n = db.sql("delete from station_hour6 where at < :before").param("before", Db.ts(now.minus(KEEP))).update();
        n += db.sql("delete from station_day where day < :before").param("before", before).update();
        days.values().forEach(d -> d.headMap(before, false).clear());
        return n;
    }

    /**
     * Forget a station's record entirely: a dropped point that expired.
     */
    public void forget(String stationId) {
        db.sql("delete from station_hour6 where station_id = :id").param("id", stationId).update();
        db.sql("delete from station_day where station_id = :id").param("id", stationId).update();
        days.remove(stationId);
    }

    public static ZoneId zoneOf(Station s) {
        try {
            return s.zone() == null ? ZoneId.of("Australia/Adelaide") : ZoneId.of(s.zone());
        } catch (RuntimeException e) {
            return ZoneId.of("Australia/Adelaide");
        }
    }

    /**
     * The Bureau's current day for a zone: the one that began at the last 9 am.
     */
    public static LocalDate dayOf(Instant at, ZoneId zone) {
        LocalDateTime local = at.atZone(zone).toLocalDateTime();
        return local.toLocalTime().isBefore(DAY_TURNS_AT) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }
}
