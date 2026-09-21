package au.gully.bureau;

import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * restart knows where the stations are before the first read answers. The values live in memory
 * only: they are ten minutes old at most and the next file replaces them, and the last few are kept
 * for the console.
 */
@Slf4j
@Service
public class StationRegistry {

    /**
     * How many readings are kept per station: an hour of the Bureau's ten-minute files.
     */
    public static final int RECENT = 6;

    private final JdbcClient db;
    private final Map<String, Station> stations = new ConcurrentHashMap<>();
    private final Map<String, Observation> latest = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Observation>> recent = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAsked = new ConcurrentHashMap<>();
    private final List<java.util.function.BiConsumer<Station, Observation>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Instant lastUpdateAt;

    public StationRegistry(JdbcClient db) {
        this.db = db;
    }

    /**
     * Who wants every reading newer than the station's last, as it arrives: the record.
     */
    public void onObservation(java.util.function.BiConsumer<Station, Observation> listener) {
        listeners.add(listener);
    }

    /**
     * The station list from the database, so the map has the stations before the first read.
     */
    public void rehydrate() {
        stations.clear();
        for (Station s : db.sql("select id, wmo_id, name, lat, lon, height_m, zone, district, state, kind from station")
                .query(StationRegistry::station).list()) {
            stations.put(s.id(), s);
        }
        db.sql("select id, last_asked_at from station where kind = :kind").param("kind", Station.POINT).query().listOfRows()
                .forEach(row -> { Instant at = Db.instant(row.get("last_asked_at")); if (at != null) lastAsked.put((String) row.get("id"), at); });
        log.info("stations rehydrated: {} ({} points of our own)", stations.size(), points().size());
    }

    private static Station station(ResultSet rs, int i) throws SQLException {
        return new Station(rs.getString("id"), rs.getString("wmo_id"), rs.getString("name"),
                rs.getDouble("lat"), rs.getDouble("lon"), (Double) rs.getObject("height_m"),
                rs.getString("zone"), rs.getString("district"), rs.getString("state"), rs.getString("kind"));
    }

    /**
     * The file, freshly read: the stations upserted and the latest values replaced.
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
            latest.put(s.id(), o);
            ArrayDeque<Observation> d = recent.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
            synchronized (d) {
                d.addFirst(o);
                while (d.size() > RECENT) {
                    d.removeLast();
                }
            }
            for (java.util.function.BiConsumer<Station, Observation> l : listeners) {
                try {
                    l.accept(s, o);
                } catch (RuntimeException e) {
                    log.warn("a listener failed on {} at {}: {}", s.id(), o.at(), e.toString());
                }
            }
        }
        lastUpdateAt = now;
        return added;
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
     * A station's last readings, newest first: up to {@link #RECENT}, as many as have arrived since the start.
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
        if (last != null && java.time.Duration.between(last, now).toMinutes() < 10) {
            return;
        }
        lastAsked.put(pointId, now);
        db.sql("update station set last_asked_at = :now, last_seen_at = :now where id = :id").param("now", Db.ts(now)).param("id", pointId).update();
    }

    public Instant lastAsked(String pointId) {
        return lastAsked.get(pointId);
    }

    /**
     * The model's current at a point, as the point's latest: kept like a reading, but the record's
     * listeners are not told - a point's days come from the archive, not from folding its fetches.
     */
    public void acceptModel(Station p, Observation o) {
        latest.put(p.id(), o);
        ArrayDeque<Observation> d = recent.computeIfAbsent(p.id(), k -> new ArrayDeque<>());
        synchronized (d) {
            d.addFirst(o);
            while (d.size() > RECENT) {
                d.removeLast();
            }
        }
    }

    /**
     * A point gone: its row and what the register held of it. Its terrain and record are the callers' to forget.
     */
    public void remove(String pointId) {
        db.sql("delete from station where id = :id and kind = :kind").param("id", pointId).param("kind", Station.POINT).update();
        stations.remove(pointId);
        latest.remove(pointId);
        recent.remove(pointId);
        lastAsked.remove(pointId);
    }
}
