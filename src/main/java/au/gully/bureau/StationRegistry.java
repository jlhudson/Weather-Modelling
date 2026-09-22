package au.gully.bureau;

import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every Bureau station the state file has ever named, and its latest values. The station list
 * comes from the file itself, never from a hand-typed table, and is kept in {@code station} so a
 * restart knows where the stations are before the first read answers. Every reading the file brings
 * is written to {@code station_reading} as published (W-15): the database is the store, and the
 * history is folded from it once a day. What lives in memory is the one cache the service keeps -
 * each station's latest and its newest few, for the feed and the wind's trend - read back from the
 * table at the start.
 */
@Slf4j
@Service
public class StationRegistry {

    /**
     * How many readings are kept per station in memory: an hour of the Bureau's ten-minute files.
     */
    public static final int RECENT = 6;
    /**
     * How long a reading is kept in the table: long enough to fold a day the housekeeping missed.
     */
    public static final Duration KEEP_READINGS = Duration.ofDays(3);

    private final JdbcClient db;
    private final Map<String, Station> stations = new ConcurrentHashMap<>();
    private final Map<String, Observation> latest = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Observation>> recent = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAsked = new ConcurrentHashMap<>();
    private volatile Instant lastUpdateAt;

    public StationRegistry(JdbcClient db) {
        this.db = db;
    }

    /**
     * The station list from the database, so the map has the stations before the first read; and
     * each station's newest readings, so a restart knows what every station last said.
     */
    public void rehydrate() {
        stations.clear();
        lastAsked.clear();
        for (Station s : db.sql("select id, wmo_id, name, lat, lon, height_m, zone, district, state, kind from station")
                .query(StationRegistry::station).list()) {
            stations.put(s.id(), s);
        }
        db.sql("select id, last_asked_at from station where kind = :kind").param("kind", Station.POINT).query().listOfRows()
                .forEach(row -> { Instant at = Db.instant(row.get("last_asked_at")); if (at != null) lastAsked.put((String) row.get("id"), at); });
        latest.clear();
        recent.clear();
        List<Observation> newest = db.sql("""
                        select * from (select *, row_number() over (partition by station_id order by at desc) as n from station_reading) r
                        where n <= :n order by station_id, at desc""")
                .param("n", RECENT).query(StationRegistry::observation).list();
        for (Observation o : newest) {
            if (!stations.containsKey(o.stationId())) {
                continue;
            }
            latest.putIfAbsent(o.stationId(), o);
            recent.computeIfAbsent(o.stationId(), k -> new ArrayDeque<>()).addLast(o);
        }
        log.info("stations rehydrated: {} ({} points of our own), the latest readings of {}", stations.size(), points().size(), latest.size());
    }

    private static Station station(ResultSet rs, int i) throws SQLException {
        return new Station(rs.getString("id"), rs.getString("wmo_id"), rs.getString("name"),
                rs.getDouble("lat"), rs.getDouble("lon"), (Double) rs.getObject("height_m"),
                rs.getString("zone"), rs.getString("district"), rs.getString("state"), rs.getString("kind"));
    }

    static Observation observation(ResultSet rs, int i) throws SQLException {
        return new Observation(rs.getString("station_id"), rs.getTimestamp("at").toInstant(),
                (Double) rs.getObject("temp_c"), (Double) rs.getObject("apparent_c"), (Double) rs.getObject("dew_point_c"),
                (Integer) rs.getObject("humidity_pct"), (Double) rs.getObject("wind_kmh"), (Integer) rs.getObject("wind_deg"), rs.getString("wind_dir"),
                (Double) rs.getObject("gust_kmh"), (Double) rs.getObject("pressure_hpa"), (Double) rs.getObject("rain_since_9am_mm"),
                (Double) rs.getObject("rain_24h_mm"), (Double) rs.getObject("max_temp_c"), (Double) rs.getObject("min_temp_c"),
                (Double) rs.getObject("visibility_km"), rs.getString("cloud"), (Integer) rs.getObject("cloud_oktas"), (Double) rs.getObject("delta_t_c"));
    }

    /**
     * The file, freshly read: the stations upserted, every reading newer than the station's last
     * written to the table and made the latest.
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
            if (previous != null && !o.at().isAfter(previous.at())) {
                continue;
            }
            write(o);
            keep(s.id(), o);
        }
        lastUpdateAt = now;
        return added;
    }

    /**
     * One reading into the table, as published; the same observation time again is the same row.
     */
    private void write(Observation o) {
        db.sql("""
                insert into station_reading (station_id, at, temp_c, apparent_c, dew_point_c, humidity_pct, wind_kmh, wind_deg, wind_dir, gust_kmh,
                  pressure_hpa, rain_since_9am_mm, rain_24h_mm, max_temp_c, min_temp_c, visibility_km, cloud, cloud_oktas, delta_t_c)
                values (:id, :at, :t, :app, :dew, :rh, :w, :deg, :dir, :g, :p, :rain9, :rain24, :max, :min, :vis, :cloud, :oktas, :dt)
                on conflict (station_id, at) do nothing""")
                .param("id", o.stationId()).param("at", Db.ts(o.at())).param("t", o.temperatureC()).param("app", o.apparentTemperatureC())
                .param("dew", o.dewPointC()).param("rh", o.humidityPct()).param("w", o.windSpeedKmh()).param("deg", o.windDirectionDeg())
                .param("dir", o.windDirection()).param("g", o.windGustKmh()).param("p", o.pressureMslHpa()).param("rain9", o.rainSince9amMm())
                .param("rain24", o.rain24hMm()).param("max", o.maxTemperatureC()).param("min", o.minTemperatureC()).param("vis", o.visibilityKm())
                .param("cloud", o.cloud()).param("oktas", o.cloudOktas()).param("dt", o.deltaTC()).update();
    }

    private void keep(String stationId, Observation o) {
        latest.put(stationId, o);
        ArrayDeque<Observation> d = recent.computeIfAbsent(stationId, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addFirst(o);
            while (d.size() > RECENT) {
                d.removeLast();
            }
        }
    }

    /**
     * A station's readings in a span, oldest first, from the table: what the day is folded from.
     */
    public List<Observation> readings(String stationId, Instant from, Instant to) {
        return db.sql("select * from station_reading where station_id = :id and at >= :from and at < :to order by at")
                .param("id", stationId).param("from", Db.ts(from)).param("to", Db.ts(to)).query(StationRegistry::observation).list();
    }

    /**
     * Readings older than {@link #KEEP_READINGS}, gone: the windows and the days are the history.
     */
    public int pruneReadings(Instant now) {
        return db.sql("delete from station_reading where at < :before").param("before", Db.ts(now.minus(KEEP_READINGS))).update();
    }

    public long readingRows() {
        Long n = db.sql("select count(*) from station_reading").query(Long.class).single();
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- reads

    public int size() {
        return stations.size();
    }

    /**
     * When the file was last taken in, or null before the first read since the start.
     */
    public Instant lastUpdateAt() {
        return lastUpdateAt;
    }

    public Collection<Station> all() {
        return stations.values().stream().sorted(Comparator.comparing(Station::id)).toList();
    }

    public Optional<Station> station(String id) {
        return Optional.ofNullable(id == null ? null : stations.get(id));
    }

    public Optional<Observation> latest(String stationId) {
        return Optional.ofNullable(stationId == null ? null : latest.get(stationId));
    }

    /**
     * A station's last readings, newest first: up to {@link #RECENT}.
     */
    public List<Observation> recent(String stationId) {
        ArrayDeque<Observation> d = recent.get(stationId);
        if (d == null) {
            return List.of();
        }
        synchronized (d) {
            return List.copyOf(d);
        }
    }

    /**
     * How many stations have reported inside a window ending now.
     */
    public long reporting(Instant since) {
        return latest.values().stream().filter(o -> o.at() != null && o.at().isAfter(since)).count();
    }

    // ---------------------------------------------------------------- the points of our own (W-7)

    public List<Station> bureau() {
        return stations.values().stream().filter(s -> !s.isPoint()).sorted(Comparator.comparing(Station::id)).toList();
    }

    public List<Station> points() {
        return stations.values().stream().filter(Station::isPoint).sorted(Comparator.comparing(Station::id)).toList();
    }

    /**
     * A point dropped: written with the moment as its first ask, and in the register from now on.
     */
    public void addPoint(Station p, Instant now) {
        db.sql("""
                insert into station (id, wmo_id, name, lat, lon, height_m, zone, district, state, kind, first_seen_at, last_seen_at, last_asked_at)
                values (:id, null, :name, :lat, :lon, :height, :zone, null, :state, :kind, :now, :now, :now)
                on conflict (id) do update set last_seen_at = excluded.last_seen_at, last_asked_at = excluded.last_asked_at""")
                .param("id", p.id()).param("name", p.name()).param("lat", p.lat()).param("lon", p.lon()).param("height", p.heightM())
                .param("zone", p.zone()).param("state", p.state()).param("kind", Station.POINT).param("now", Db.ts(now)).update();
        stations.put(p.id(), p);
        lastAsked.put(p.id(), now);
    }

    /**
     * A point asked about again: what its expiry counts from.
     */
    public void touch(String pointId, Instant now) {
        Instant last = lastAsked.get(pointId);
        if (last != null && Duration.between(last, now).toMinutes() < 10) {
            return;
        }
        lastAsked.put(pointId, now);
        db.sql("update station set last_asked_at = :now, last_seen_at = :now where id = :id").param("now", Db.ts(now)).param("id", pointId).update();
    }

    public Instant lastAsked(String pointId) {
        return lastAsked.get(pointId);
    }

    /**
     * The model's current at a point, as the point's latest: written and kept like a reading. A
     * point's days come from the archive, never from folding its fetches.
     */
    public void acceptModel(Station p, Observation o) {
        if (o.at() != null) {
            write(o);
        }
        keep(p.id(), o);
    }

    /**
     * A point gone: its rows and what the register held of it. Its terrain and record are the callers' to forget.
     */
    public void remove(String pointId) {
        db.sql("delete from station_reading where station_id = :id").param("id", pointId).update();
        db.sql("delete from station where id = :id and kind = :kind").param("id", pointId).param("kind", Station.POINT).update();
        stations.remove(pointId);
        latest.remove(pointId);
        recent.remove(pointId);
        lastAsked.remove(pointId);
    }
}
