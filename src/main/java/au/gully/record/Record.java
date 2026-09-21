package au.gully.record;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * What a station's readings are kept as once the file has moved on (W-6). Every observation goes
 * into the open six-hour {@link Window} for its station; when a reading arrives past the window's
 * end the window is written to {@code station_hour6} and a new one opens. The first reading at or
 * after 9 am closes the Bureau's day that ended there: its rain is the total to 9 am that reading
 * publishes, its maximum the highest of the day's windows and of the running maximum the Bureau
 * published just before 9 am. A day is written only when it has a rain figure; one with none is
 * left absent for the archive to fill.
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
    public static final int KEEP_DAYS = 548;
    /**
     * How much record the drought wants behind it.
     */
    public static final int SPIN_UP_DAYS = 365;
    /**
     * The windows end on these local hours; the day turns on the second.
     */
    static final int[] BOUNDARY_HOURS = {3, 9, 15, 21};
    public static final LocalTime DAY_TURNS_AT = LocalTime.of(9, 0);

    public static final String SOURCE_BUREAU = "bureau";
    public static final String SOURCE_ARCHIVE = "archive";

    private final JdbcClient db;
    private final Map<String, Window> open = new ConcurrentHashMap<>();
    private final Map<String, Deque<Hour6>> lastWindows = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<LocalDate, Day>> days = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> lastDayClosed = new ConcurrentHashMap<>();
    private final Map<String, Long> versions = new ConcurrentHashMap<>();

    public Record(JdbcClient db, au.gully.bureau.StationRegistry stations) {
        this.db = db;
        if (stations != null) {
            stations.onObservation(this::accept);
        }
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

    // ---------------------------------------------------------------- the start

    public void rehydrate() {
        days.clear();
        lastWindows.clear();
        Instant since = Instant.now().minus(KEEP);
        db.sql("select station_id, day, rain_mm, max_temp_c, source from station_day where day >= :since order by day")
                .param("since", LocalDate.ofInstant(since, ZoneId.of("UTC"))).query().listOfRows().forEach(row ->
                        daysOf((String) row.get("station_id")).put(Db.date(row.get("day")),
                                new Day(Db.date(row.get("day")), Db.dbl(row.get("rain_mm")), Db.dbl(row.get("max_temp_c")), (String) row.get("source"))));
        db.sql("""
                select * from (select station_id, at, readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct, wind_mean_kmh, wind_max_kmh,
                  gust_max_kmh, rain_since_9am_mm, rain_24h_mm, published_max_c, row_number() over (partition by station_id order by at desc) as n
                  from station_hour6) w where n <= 4 order by station_id, at""").query().listOfRows().forEach(row ->
                lastWindows.computeIfAbsent((String) row.get("station_id"), k -> new ArrayDeque<>()).addLast(hour6(row)));
        long rows = days.values().stream().mapToLong(Map::size).sum();
        log.info("record rehydrated: {} days over {} stations, the last windows of {}", rows, days.size(), lastWindows.size());
    }

    private static Hour6 hour6(Map<String, Object> row) {
        return new Hour6(Db.instant(row.get("at")), Db.integer(row.get("readings")), Db.dbl(row.get("temp_min_c")), Db.dbl(row.get("temp_max_c")),
                Db.dbl(row.get("temp_mean_c")), Db.integer(row.get("rh_min_pct")), Db.integer(row.get("rh_max_pct")), Db.dbl(row.get("wind_mean_kmh")),
                Db.dbl(row.get("wind_max_kmh")), Db.dbl(row.get("gust_max_kmh")), Db.dbl(row.get("rain_since_9am_mm")), Db.dbl(row.get("rain_24h_mm")),
                Db.dbl(row.get("published_max_c")));
    }

    private NavigableMap<LocalDate, Day> daysOf(String stationId) {
        return days.computeIfAbsent(stationId, k -> new ConcurrentSkipListMap<>());
    }

    // ---------------------------------------------------------------- the fold

    /**
     * One reading, newer than the station's last: into its window, and the window and the day
     * closed when it is past them.
     */
    public void accept(Station s, Observation o) {
        if (o.at() == null) {
            return;
        }
        ZoneId zone = zoneOf(s);
        Instant end = boundaryAfter(o.at(), zone);
        Window w = open.get(s.id());
        if (w != null && !w.end.equals(end)) {
            closeWindow(s, w);
            w = null;
        }
        ZonedDateTime local = o.at().atZone(zone);
        if (!local.toLocalTime().isBefore(DAY_TURNS_AT)) {
            LocalDate ended = local.toLocalDate().minusDays(1);
            LocalDate last = lastDayClosed.get(s.id());
            if ((last == null || ended.isAfter(last)) && !daysOf(s.id()).containsKey(ended)) {
                closeDay(s, ended, o, zone);
            }
            lastDayClosed.put(s.id(), ended);
        }
        if (w == null) {
            w = new Window(end);
            open.put(s.id(), w);
        }
        w.add(o);
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

    /**
     * A window past its end: written, and kept among the station's last four for the day's close.
     */
    void closeWindow(Station s, Window w) {
        Hour6 h = hour6(w);
        db.sql("""
                insert into station_hour6 (station_id, at, readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct,
                  wind_mean_kmh, wind_max_kmh, gust_max_kmh, rain_since_9am_mm, rain_24h_mm, published_max_c)
                values (:id, :at, :n, :tmin, :tmax, :tmean, :rhmin, :rhmax, :wmean, :wmax, :gmax, :rain, :rain24, :pmax)
                on conflict (station_id, at) do update set readings = station_hour6.readings + excluded.readings,
                  temp_min_c = least(station_hour6.temp_min_c, excluded.temp_min_c), temp_max_c = greatest(station_hour6.temp_max_c, excluded.temp_max_c),
                  temp_mean_c = coalesce(excluded.temp_mean_c, station_hour6.temp_mean_c),
                  rh_min_pct = least(station_hour6.rh_min_pct, excluded.rh_min_pct), rh_max_pct = greatest(station_hour6.rh_max_pct, excluded.rh_max_pct),
                  wind_mean_kmh = coalesce(excluded.wind_mean_kmh, station_hour6.wind_mean_kmh), wind_max_kmh = greatest(station_hour6.wind_max_kmh, excluded.wind_max_kmh),
                  gust_max_kmh = greatest(station_hour6.gust_max_kmh, excluded.gust_max_kmh), rain_since_9am_mm = coalesce(excluded.rain_since_9am_mm, station_hour6.rain_since_9am_mm),
                  rain_24h_mm = coalesce(excluded.rain_24h_mm, station_hour6.rain_24h_mm), published_max_c = greatest(station_hour6.published_max_c, excluded.published_max_c)""")
                .param("id", s.id()).param("at", Db.ts(h.at())).param("n", h.readings()).param("tmin", h.tMin()).param("tmax", h.tMax())
                .param("tmean", h.tMean()).param("rhmin", h.rhMin()).param("rhmax", h.rhMax()).param("wmean", h.wMean()).param("wmax", h.wMax())
                .param("gmax", h.gMax()).param("rain", h.rainSince9am()).param("rain24", h.rain24h()).param("pmax", h.publishedMax()).update();
        keepWindow(s, w);
    }

    void keepWindow(Station s, Window w) {
        Hour6 h = hour6(w);
        Deque<Hour6> d = lastWindows.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(h);
            while (d.size() > 4) {
                d.removeFirst();
            }
        }
    }

    /**
     * The day that ended at 9 am, from the reading at or after 9 am that publishes its total, and
     * the windows that fell inside it. No total, no row.
     */
    private void closeDay(Station s, LocalDate day, Observation first, ZoneId zone) {
        Double rain = first.rain24hMm();
        if (rain == null) {
            return;
        }
        Instant from = day.atTime(DAY_TURNS_AT).atZone(zone).toInstant();
        Instant to = day.plusDays(1).atTime(DAY_TURNS_AT).atZone(zone).toInstant();
        Double max = null;
        Deque<Hour6> d = lastWindows.get(s.id());
        if (d != null) {
            synchronized (d) {
                for (Hour6 h : d) {
                    if (h.at().isAfter(from) && !h.at().isAfter(to)) {
                        max = maxOf(max, h.tMax());
                        if (h.at().equals(to)) {
                            max = maxOf(max, h.publishedMax());
                        }
                    }
                }
            }
        }
        if (max == null) {
            // No window of the day was seen - the service was not running - so the archive will have the day.
            return;
        }
        put(s.id(), new Day(day, rain, max, SOURCE_BUREAU), true);
    }

    private static Double maxOf(Double a, Double b) {
        return a == null ? b : b == null ? a : Double.valueOf(Math.max(a, b));
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
            versions.merge(stationId, 1L, Long::sum);
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
     * Changes when the station's days do: what a memo of the drought is keyed by.
     */
    public long version(String stationId) {
        return versions.getOrDefault(stationId, 0L);
    }

    /**
     * The open window's rain so far, for the drought factor's last day.
     */
    public Double rainSoFar(String stationId) {
        Window w = open.get(stationId);
        return w == null ? null : w.rainSince9am;
    }

    /**
     * The last windows, newest first, for the console.
     */
    public List<Map<String, Object>> recentWindows(String stationId, int limit) {
        return db.sql("select at, readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct, wind_mean_kmh, wind_max_kmh, gust_max_kmh,"
                        + " rain_since_9am_mm, rain_24h_mm, published_max_c from station_hour6 where station_id = :id order by at desc limit :n")
                .param("id", stationId).param("n", Math.max(1, Math.min(limit, 200))).query().listOfRows();
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
        open.remove(stationId);
        lastWindows.remove(stationId);
        lastDayClosed.remove(stationId);
        versions.merge(stationId, 1L, Long::sum);
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
