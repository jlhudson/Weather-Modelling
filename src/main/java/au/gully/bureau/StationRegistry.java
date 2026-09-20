package au.gully.bureau;

import au.gully.hexagons.Cell;
import au.gully.hexagons.Geo;
import au.gully.hexagons.Grid;
import au.gully.hexagons.Reach;
import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every Bureau station the state files have ever named, its latest values, and the six-hourly ledger
 * (docs/06 item 12, W-19): one row per station every six hours, holding the day's rain to 9 am and
 * the running maximum temperature — enough to step a soil moisture deficit forward a day at a time —
 * and the consolidation of the readings seen in the six hours, which is the station's history, kept
 * five years.
 * <p>
 * The station list comes from the files themselves, never from a hand-typed table, and is kept in
 * {@code station} so a restart knows where the stations are before the first poll answers. The
 * latest values live in memory only: they are ten minutes old at most and the next file replaces them.
 */
@Slf4j
@Service
public class StationRegistry {

    /**
     * The ledger's cadence.
     */
    static final Duration LEDGER_EVERY = Duration.ofHours(6);

    /**
     * The Bureau's day: rain is totalled to 9 am local, and the running maximum is reset at 6 am.
     */
    static final LocalTime RAIN_DAY_ENDS = LocalTime.of(9, 0);

    private final JdbcClient db;
    private final Map<String, Station> stations = new ConcurrentHashMap<>();
    private final Map<String, Observation> latest = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastLedgered = new ConcurrentHashMap<>();
    /**
     * Today's maximum per station from every ten-minute value seen, which is finer than the
     * six-hourly ledger and is what the ledger row carries as the day's maximum.
     */
    private final Map<String, DayMax> dayMax = new ConcurrentHashMap<>();
    /** The last few readings per station, newest first, for the wind change and the console (W-16). */
    private final Map<String, java.util.ArrayDeque<Observation>> recent = new ConcurrentHashMap<>();
    /**
     * The readings seen per station since its last ledger row, consolidated as they arrive (W-19):
     * the window's extremes and means go onto the row, and the window starts again. Empty after a
     * restart, so the first row after one consolidates what has been seen since.
     */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * How many readings are kept per station: six, an hour of the Bureau's ten-minute files, which
     * is the window a wind change is looked for in.
     */
    public static final int RECENT = 6;
    /**
     * Per grid and reach, per station: the hexagon ids it counts for. Cleared when a station's
     * details change, and whole when the reach does (the key carries the reach, so a stale entry
     * could not be read anyway; the clearing is so old reaches do not pile up).
     */
    private final Map<String, Map<String, List<String>>> reach = new ConcurrentHashMap<>();
    private final Reach stationReach;
    private volatile Instant lastUpdateAt;

    public StationRegistry(JdbcClient db, Reach stationReach) {
        this.db = db;
        this.stationReach = stationReach;
        if (stationReach != null) {
            stationReach.onChange(km -> reach.clear());
        }
    }

    /**
     * Phase 2: the station list, so the hexagons can find their stations before the first poll.
     */
    public void rehydrate() {
        stations.clear();
        reach.clear();
        for (Station s : db.sql("select id, wmo_id, name, lat, lon, height_m, zone, district, state from station")
                .query(StationRegistry::station).list()) {
            stations.put(s.id(), s);
        }
        for (Map<String, Object> row : db.sql("select station_id, max(at) as at from station_sample group by station_id").query().listOfRows()) {
            Instant at = Db.instant(row.get("at"));
            if (at != null) {
                lastLedgered.put((String) row.get("station_id"), at);
            }
        }
        // The last readings, newest first, so a wind change is seen from the first file after a restart.
        recent.clear();
        for (Map<String, Object> row : db.sql("select station_id, at, temperature_c, humidity_pct, wind_kmh, wind_deg, gust_kmh, rain_since_9am_mm"
                + " from station_recent order by station_id, at desc").query().listOfRows()) {
            String id = (String) row.get("station_id");
            java.util.ArrayDeque<Observation> d = recent.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
            if (d.size() < RECENT) {
                d.addLast(new Observation(id, Db.instant(row.get("at")), Db.dbl(row.get("temperature_c")), null, null,
                        row.get("humidity_pct") == null ? null : ((Number) row.get("humidity_pct")).intValue(),
                        Db.dbl(row.get("wind_kmh")), row.get("wind_deg") == null ? null : ((Number) row.get("wind_deg")).intValue(),
                        null, Db.dbl(row.get("gust_kmh")), null, Db.dbl(row.get("rain_since_9am_mm")), null, null, null, null, null, null, null));
            }
        }
        log.info("stations rehydrated: {}, with recent readings for {}", stations.size(), recent.size());
    }

    private static Station station(ResultSet rs, int i) throws SQLException {
        return new Station(rs.getString("id"), rs.getString("wmo_id"), rs.getString("name"),
                rs.getDouble("lat"), rs.getDouble("lon"), (Double) rs.getObject("height_m"),
                rs.getString("zone"), rs.getString("district"), rs.getString("state"));
    }

    /**
     * One state's file, freshly read: the stations upserted, the latest values replaced, and a ledger
     * row for every station whose last row is six hours old.
     *
     * @return how many stations were new to the register
     */
    @Transactional
    public int accept(List<StationFile.StationReading> readings, Instant now) {
        int added = 0;
        for (StationFile.StationReading r : readings) {
            Station s = r.station();
            Station known = stations.put(s.id(), s);
            if (known == null || known.lat() != s.lat() || known.lon() != s.lon()) {
                reach.values().forEach(m -> m.remove(s.id()));
            }
            if (known == null || !known.equals(s)) {
                db.sql("""
                        insert into station (id, wmo_id, name, lat, lon, height_m, zone, district, state, first_seen_at, last_seen_at)
                        values (:id, :wmo, :name, :lat, :lon, :height, :zone, :district, :state, :now, :now)
                        on conflict (id) do update set wmo_id = excluded.wmo_id, name = excluded.name, lat = excluded.lat,
                          lon = excluded.lon, height_m = excluded.height_m, zone = excluded.zone, district = excluded.district,
                          state = excluded.state, last_seen_at = excluded.last_seen_at""")
                        .param("id", s.id()).param("wmo", s.wmoId()).param("name", s.name())
                        .param("lat", s.lat()).param("lon", s.lon()).param("height", s.heightM())
                        .param("zone", s.zone()).param("district", s.district()).param("state", s.state())
                        .param("now", Db.ts(now)).update();
                if (known == null) {
                    added++;
                }
            }
            Observation o = r.observation();
            if (o == null || o.at() == null) {
                continue;
            }
            Observation previous = latest.get(s.id());
            if (previous != null && previous.at().isAfter(o.at())) {
                continue;
            }
            latest.put(s.id(), o);
            if (previous == null || o.at().isAfter(previous.at())) {
                java.util.ArrayDeque<Observation> d = recent.computeIfAbsent(s.id(), k -> new java.util.ArrayDeque<>());
                synchronized (d) {
                    d.addFirst(o);
                    while (d.size() > RECENT) {
                        d.removeLast();
                    }
                }
                keepRecent(s, o);
                windows.computeIfAbsent(s.id(), k -> new Window()).add(o);
            }
            trackDayMax(s, o);
            Instant last = lastLedgered.get(s.id());
            if (last == null || Duration.between(last, o.at()).compareTo(LEDGER_EVERY) >= 0) {
                ledger(s, o, windows.remove(s.id()));
                lastLedgered.put(s.id(), o.at());
            }
        }
        lastUpdateAt = now;
        return added;
    }

    private void trackDayMax(Station s, Observation o) {
        ZoneId zone = zoneOf(s);
        LocalDate day = o.at().atZone(zone).toLocalDate();
        Double t = o.temperatureC();
        Double published = o.maxTemperatureC();
        double candidate = Math.max(t == null ? Double.NEGATIVE_INFINITY : t, published == null ? Double.NEGATIVE_INFINITY : published);
        if (candidate == Double.NEGATIVE_INFINITY) {
            return;
        }
        dayMax.merge(s.id(), new DayMax(day, candidate), (old, fresh) ->
                old.day().equals(fresh.day()) ? new DayMax(day, Math.max(old.maxC(), fresh.maxC())) : fresh);
    }

    /**
     * One of the last readings, written, and the ones beyond the sixth dropped.
     */
    private void keepRecent(Station s, Observation o) {
        db.sql("""
                insert into station_recent (station_id, at, temperature_c, humidity_pct, wind_kmh, wind_deg, gust_kmh, rain_since_9am_mm)
                values (:id, :at, :t, :rh, :w, :dir, :g, :rain) on conflict (station_id, at) do nothing""")
                .param("id", s.id()).param("at", Db.ts(o.at())).param("t", o.temperatureC()).param("rh", o.humidityPct())
                .param("w", o.windSpeedKmh()).param("dir", o.windDirectionDeg()).param("g", o.windGustKmh()).param("rain", o.rainSince9amMm())
                .update();
        db.sql("delete from station_recent where station_id = :id and at < (select at from station_recent where station_id = :id order by at desc offset :keep limit 1)")
                .param("id", s.id()).param("keep", RECENT - 1).update();
    }

    /**
     * The ledger row: the values at the moment, the day's rain and running maximum, and the window's
     * consolidation - what the readings since the last row did between them (W-19).
     */
    private void ledger(Station s, Observation o, Window w) {
        DayMax max = dayMax.get(s.id());
        Double dayMaximum = max == null ? o.maxTemperatureC() : Double.valueOf(max.maxC());
        if (w == null) {
            w = new Window();
            w.add(o);
        }
        db.sql("""
                insert into station_sample (station_id, at, temperature_c, max_temperature_c, min_temperature_c,
                  rain_since_9am_mm, rain_24h_mm, humidity_pct, wind_speed_kmh, wind_direction_deg, wind_gust_kmh, pressure_hpa,
                  readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct, wind_mean_kmh, wind_max_kmh, gust_max_kmh)
                values (:id, :at, :t, :max, :min, :rain, :rain24, :rh, :wind, :dir, :gust, :p,
                  :n, :tmin, :tmax, :tmean, :rhmin, :rhmax, :wmean, :wmax, :gmax)
                on conflict (station_id, at) do nothing""")
                .param("id", s.id()).param("at", Db.ts(o.at())).param("t", o.temperatureC()).param("max", dayMaximum)
                .param("min", o.minTemperatureC()).param("rain", o.rainSince9amMm()).param("rain24", o.rain24hMm())
                .param("rh", o.humidityPct()).param("wind", o.windSpeedKmh()).param("dir", o.windDirectionDeg())
                .param("gust", o.windGustKmh()).param("p", o.pressureMslHpa())
                .param("n", w.readings).param("tmin", w.tMin()).param("tmax", w.tMax()).param("tmean", w.tMean())
                .param("rhmin", w.rhMin).param("rhmax", w.rhMax).param("wmean", w.wMean()).param("wmax", w.wMax).param("gmax", w.gMax)
                .update();
    }

    /**
     * The readings since a station's last ledger row, consolidated as they arrive: how many, the
     * temperature's extremes and mean, the humidity's extremes, the wind's mean and maximum, the
     * strongest gust. Nulls where no reading in the window had the value.
     */
    static final class Window {
        int readings;
        private double tSum;
        private int tN;
        private Double tMin, tMax;
        Integer rhMin, rhMax;
        private double wSum;
        private int wN;
        Double wMax, gMax;

        void add(Observation o) {
            readings++;
            Double t = o.temperatureC();
            if (t != null) {
                tSum += t;
                tN++;
                tMin = tMin == null ? t : Double.valueOf(Math.min(tMin, t));
                tMax = tMax == null ? t : Double.valueOf(Math.max(tMax, t));
            }
            Integer rh = o.humidityPct();
            if (rh != null) {
                rhMin = rhMin == null ? rh : Integer.valueOf(Math.min(rhMin, rh));
                rhMax = rhMax == null ? rh : Integer.valueOf(Math.max(rhMax, rh));
            }
            Double w = o.windSpeedKmh();
            if (w != null) {
                wSum += w;
                wN++;
                wMax = wMax == null ? w : Double.valueOf(Math.max(wMax, w));
            }
            Double g = o.windGustKmh();
            if (g != null) {
                gMax = gMax == null ? g : Double.valueOf(Math.max(gMax, g));
            }
        }

        Double tMin() {
            return tMin;
        }

        Double tMax() {
            return tMax;
        }

        Double tMean() {
            return tN == 0 ? null : Math.round(tSum / tN * 10) / 10.0;
        }

        Double wMean() {
            return wN == 0 ? null : Math.round(wSum / wN * 10) / 10.0;
        }
    }

    // ---------------------------------------------------------------- reads

    public int size() {
        return stations.size();
    }

    public Instant lastUpdateAt() {
        return lastUpdateAt;
    }

    public Collection<Station> all() {
        return List.copyOf(stations.values());
    }

    public Optional<Station> station(String id) {
        return Optional.ofNullable(id == null ? null : stations.get(id));
    }

    /**
     * A station's last readings, newest first: up to {@link #RECENT}, as many as have arrived since the start.
     */
    public List<Observation> recent(String stationId) {
        java.util.ArrayDeque<Observation> d = recent.get(stationId);
        if (d == null) {
            return List.of();
        }
        synchronized (d) {
            return List.copyOf(d);
        }
    }

    /**
     * The wind change a station has just measured, if any, from its last readings.
     */
    public Optional<WindShift> windShift(String stationId) {
        return WindShift.of(recent(stationId));
    }

    /**
     * Where a station's wind has mostly been against where it is now: the mean of its readings
     * before the latest, and the latest, from the same last readings.
     */
    public Optional<WindTrend> windTrend(String stationId) {
        return WindTrend.of(recent(stationId));
    }

    public Optional<Observation> latest(String stationId) {
        return Optional.ofNullable(stationId == null ? null : latest.get(stationId));
    }

    /**
     * The nearest station to a point, with the distance, or empty when no file has been read yet.
     */
    public Optional<Nearest> nearest(double lat, double lon) {
        Station best = null;
        double bestMetres = Double.MAX_VALUE;
        for (Station s : stations.values()) {
            double d = Geo.haversineMetres(lat, lon, s.lat(), s.lon());
            if (d < bestMetres) {
                bestMetres = d;
                best = s;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Nearest(best, bestMetres / 1000.0));
    }

    /**
     * The hexagon's own station: the one nearest its centre where several count for it. The others
     * are not lost - "now" is blended from all of them (Interpolation.inCell) - but one is the
     * hexagon's for the links, the logs and the drought ledger. A station counts for the hexagon it
     * is in and for any neighbour whose edge is within the reach in force ({@link Reach}).
     */
    public Optional<Station> inCell(Grid grid, Cell cell) {
        return stations.values().stream()
                .filter(s -> reaches(grid, s).contains(cell.id()))
                .min(Comparator.comparingDouble((Station s) -> Grid.planarMetres(cell.lat(), cell.lon(), s.lat(), s.lon()))
                        .thenComparing(Station::id));
    }

    /**
     * The stations counting for any of the cells - inside one, or within reach of its edge - each once.
     */
    public List<Station> inCells(Grid grid, Collection<Cell> cells) {
        Set<String> ids = new HashSet<>();
        for (Cell c : cells) {
            ids.add(c.id());
        }
        return stations.values().stream()
                .filter(s -> { for (String id : reaches(grid, s)) { if (ids.contains(id)) return true; } return false; })
                .sorted(Comparator.comparing(Station::id))
                .toList();
    }

    /**
     * The ids of the hexagons a station counts for: the one it is in and any within its reach.
     */
    public List<String> hexagonsOf(Grid grid, String stationId) {
        Station s = stations.get(stationId);
        return s == null ? List.of() : reaches(grid, s);
    }

    /**
     * Every station with a wind change in its last readings, with the change.
     */
    public Map<String, WindShift> windShifts() {
        Map<String, WindShift> out = new java.util.HashMap<>();
        for (String id : recent.keySet()) {
            windShift(id).ifPresent(w -> out.put(id, w));
        }
        return out;
    }

    /**
     * The ids of the hexagons a station counts for, worked out once per station, grid and reach: a
     * station does not move, and the map asks this for every hexagon on every render.
     */
    private List<String> reaches(Grid grid, Station s) {
        double km = reachKm();
        Map<String, List<String>> forGrid = reach.computeIfAbsent(grid.spec() + "; reach " + km, k -> new ConcurrentHashMap<>());
        return forGrid.computeIfAbsent(s.id(), k -> grid.cellsReaching(s.lat(), s.lon(), km).stream().map(Cell::id).toList());
    }

    /**
     * The reach in force: the console's, or the default where the register stands alone (a test).
     */
    public double reachKm() {
        return stationReach == null ? Grid.DEFAULT_STATION_REACH_KM : stationReach.km();
    }

    /**
     * Stations within a radius of a point, nearest first.
     */
    public List<Nearest> within(double lat, double lon, double radiusKm) {
        List<Nearest> out = new ArrayList<>();
        for (Station s : stations.values()) {
            double km = Geo.haversineMetres(lat, lon, s.lat(), s.lon()) / 1000.0;
            if (km <= radiusKm) {
                out.add(new Nearest(s, km));
            }
        }
        out.sort(Comparator.comparingDouble(Nearest::distanceKm));
        return out;
    }

    /**
     * The days the ledger can answer for a set of stations, as one row per calendar day: the rain
     * (the 9 am total, attributed to the day most of it fell) and the maximum temperature, each the
     * mean and the max across the stations that reported. Days with no station row are absent.
     *
     * @param from inclusive
     * @param to   inclusive
     */
    public SortedMap<LocalDate, DailyInput> daily(List<Station> of, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, DailyInput> out = new TreeMap<>();
        if (of.isEmpty()) {
            return out;
        }
        List<String> ids = of.stream().map(Station::id).toList();
        Map<String, ZoneId> zones = new HashMap<>();
        of.forEach(s -> zones.put(s.id(), zoneOf(s)));
        Instant since = from.minusDays(1).atStartOfDay(zones.values().iterator().next()).toInstant();
        Instant until = to.plusDays(2).atStartOfDay(zones.values().iterator().next()).toInstant();
        Map<LocalDate, Map<String, Double>> rainByDay = new HashMap<>();
        Map<LocalDate, Map<String, Double>> maxByDay = new HashMap<>();
        db.sql("select station_id, at, temperature_c, max_temperature_c, rain_since_9am_mm, rain_24h_mm from station_sample"
                        + " where station_id in (:ids) and at >= :since and at < :until order by at")
                .param("ids", ids).param("since", Db.ts(since)).param("until", Db.ts(until))
                .query().listOfRows().forEach(row -> {
                    String id = (String) row.get("station_id");
                    Instant at = Db.instant(row.get("at"));
                    if (at == null) {
                        return;
                    }
                    ZonedDateTime local = at.atZone(zones.getOrDefault(id, ZoneId.of("Australia/Adelaide")));
                    LocalDate day = local.toLocalDate();
                    // The 9 am total is the previous rain day's; a sample before 9 am is still inside it.
                    Double rain24 = (Double) row.get("rain_24h_mm");
                    if (rain24 != null && !local.toLocalTime().isBefore(RAIN_DAY_ENDS)) {
                        rainByDay.computeIfAbsent(day.minusDays(1), d -> new HashMap<>()).merge(id, rain24, Math::max);
                    }
                    Double max = (Double) row.get("max_temperature_c");
                    Double t = (Double) row.get("temperature_c");
                    // Two nullable values: never a mixed ternary, which unboxes the null.
                    Double candidate = max == null ? t : t == null ? max : Double.valueOf(Math.max(max, t));
                    if (candidate != null) {
                        maxByDay.computeIfAbsent(day, d -> new HashMap<>()).merge(id, candidate, Math::max);
                    }
                });
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<String, Double> rain = rainByDay.get(d);
            Map<String, Double> max = maxByDay.get(d);
            if (rain == null && max == null) {
                continue;
            }
            Double meanRain = rain == null ? null : rain.values().stream().mapToDouble(v -> v).average().orElse(Double.NaN);
            Double maxTemp = max == null ? null : max.values().stream().mapToDouble(v -> v).max().orElse(Double.NaN);
            out.put(d, new DailyInput(d, meanRain == null || meanRain.isNaN() ? null : meanRain,
                    maxTemp == null || maxTemp.isNaN() ? null : maxTemp,
                    (rain == null ? 0 : rain.size()) + (max == null ? 0 : max.size())));
        }
        return out;
    }

    /**
     * The ledger, most recent first, for the console.
     */
    public List<Map<String, Object>> recentSamples(String stationId, int limit) {
        return db.sql("select at, temperature_c, max_temperature_c, rain_since_9am_mm, rain_24h_mm, humidity_pct, wind_speed_kmh,"
                        + " readings, temp_min_c, temp_max_c, temp_mean_c, rh_min_pct, rh_max_pct, wind_mean_kmh, wind_max_kmh, gust_max_kmh"
                        + " from station_sample where station_id = :id order by at desc limit :n")
                .param("id", stationId).param("n", Math.max(1, Math.min(limit, 500))).query().listOfRows();
    }

    public long ledgerRows() {
        Long n = db.sql("select count(*) from station_sample").query(Long.class).single();
        return n == null ? 0 : n;
    }

    static ZoneId zoneOf(Station s) {
        try {
            return s.zone() == null ? ZoneId.of("Australia/Adelaide") : ZoneId.of(s.zone());
        } catch (RuntimeException e) {
            return ZoneId.of("Australia/Adelaide");
        }
    }

    private record DayMax(LocalDate day, double maxC) {
    }

    /**
     * A station and how far away it is.
     */
    public record Nearest(Station station, double distanceKm) {
    }

    /**
     * One calendar day of drought inputs from the stations of an area.
     *
     * @param rainMm          mean of the stations' 9 am totals, or null when none reported one
     * @param maxTemperatureC the warmest any station in the area got, or null
     * @param reports         how many station-values went into it
     */
    public record DailyInput(LocalDate date, Double rainMm, Double maxTemperatureC, int reports) {
    }
}
