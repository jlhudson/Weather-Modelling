package au.gully.bureau;

import au.gully.hexagons.Cell;
import au.gully.hexagons.Geo;
import au.gully.hexagons.Grid;
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
 * Every Bureau station the state files have ever named, its latest values, and the compact ledger
 * behind the drought maths (docs/06 item 12): one row per station every six hours, holding the
 * day's rain to 9 am and the running maximum temperature — enough to step a soil moisture deficit
 * forward a day at a time, and nothing like the history, which stations never write.
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
    private volatile Instant lastUpdateAt;

    public StationRegistry(JdbcClient db) {
        this.db = db;
    }

    /**
     * Phase 2: the station list, so the hexagons can find their stations before the first poll.
     */
    public void rehydrate() {
        stations.clear();
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
        log.info("stations rehydrated: {}", stations.size());
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
            trackDayMax(s, o);
            Instant last = lastLedgered.get(s.id());
            if (last == null || Duration.between(last, o.at()).compareTo(LEDGER_EVERY) >= 0) {
                ledger(s, o);
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

    private void ledger(Station s, Observation o) {
        DayMax max = dayMax.get(s.id());
        Double dayMaximum = max == null ? o.maxTemperatureC() : Double.valueOf(max.maxC());
        db.sql("""
                insert into station_sample (station_id, at, temperature_c, max_temperature_c, min_temperature_c,
                  rain_since_9am_mm, rain_24h_mm, humidity_pct, wind_speed_kmh, wind_direction_deg, wind_gust_kmh, pressure_hpa)
                values (:id, :at, :t, :max, :min, :rain, :rain24, :rh, :wind, :dir, :gust, :p)
                on conflict (station_id, at) do nothing""")
                .param("id", s.id()).param("at", Db.ts(o.at())).param("t", o.temperatureC()).param("max", dayMaximum)
                .param("min", o.minTemperatureC()).param("rain", o.rainSince9amMm()).param("rain24", o.rain24hMm())
                .param("rh", o.humidityPct()).param("wind", o.windSpeedKmh()).param("dir", o.windDirectionDeg())
                .param("gust", o.windGustKmh()).param("p", o.pressureMslHpa())
                .update();
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
     * The station inside a cell, if there is one; the first by id where there are several.
     */
    public Optional<Station> inCell(Grid grid, Cell cell) {
        return stations.values().stream()
                .filter(s -> grid.cellOf(s.lat(), s.lon()).id().equals(cell.id()))
                .min(Comparator.comparing(Station::id));
    }

    /**
     * The stations inside any of the cells, for the drought area.
     */
    public List<Station> inCells(Grid grid, Collection<Cell> cells) {
        Set<String> ids = new HashSet<>();
        for (Cell c : cells) {
            ids.add(c.id());
        }
        return stations.values().stream()
                .filter(s -> ids.contains(grid.cellOf(s.lat(), s.lon()).id()))
                .sorted(Comparator.comparing(Station::id))
                .toList();
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
        return db.sql("select at, temperature_c, max_temperature_c, rain_since_9am_mm, rain_24h_mm, humidity_pct, wind_speed_kmh"
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
