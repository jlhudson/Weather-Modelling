package au.gully.reach;

import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every station's terrain, in the {@code terrain} table and in memory: written once when sampled,
 * read back whole at start. A station that has moved is sampled again — the row carries the
 * position it was sampled at.
 */
@Slf4j
@Repository
public class TerrainStore {

    private final JdbcClient db;
    private final Map<String, Terrain> byStation = new ConcurrentHashMap<>();

    public TerrainStore(JdbcClient db) {
        this.db = db;
    }

    public void rehydrate() {
        byStation.clear();
        db.sql("select station_id, lat, lon, elevations, sampled_at, calls from terrain").query().listOfRows().forEach(row ->
                byStation.put((String) row.get("station_id"), Terrain.fromBytes((String) row.get("station_id"),
                        Db.dbl(row.get("lat")), Db.dbl(row.get("lon")), (byte[]) row.get("elevations"),
                        Db.instant(row.get("sampled_at")), Db.integer(row.get("calls")))));
        log.info("terrain rehydrated for {} stations", byStation.size());
    }

    public void put(Terrain t) {
        db.sql("""
                insert into terrain (station_id, lat, lon, elevations, sampled_at, calls)
                values (:id, :lat, :lon, :elevations, :at, :calls)
                on conflict (station_id) do update set lat = excluded.lat, lon = excluded.lon,
                  elevations = excluded.elevations, sampled_at = excluded.sampled_at, calls = excluded.calls""")
                .param("id", t.stationId()).param("lat", t.lat()).param("lon", t.lon()).param("elevations", t.toBytes())
                .param("at", Db.ts(t.sampledAt())).param("calls", t.calls()).update();
        byStation.put(t.stationId(), t);
    }

    public Optional<Terrain> get(String stationId) {
        return Optional.ofNullable(stationId == null ? null : byStation.get(stationId));
    }

    public Collection<Terrain> all() {
        return List.copyOf(byStation.values());
    }

    public int size() {
        return byStation.size();
    }

    /**
     * A station's terrain gone: a dropped point that expired.
     */
    public void remove(String stationId) {
        db.sql("delete from terrain where station_id = :id").param("id", stationId).update();
        byStation.remove(stationId);
    }

    /**
     * Whether the terrain held for a station was sampled where the station now is: a station that
     * has moved more than about a hundred metres wants sampling again.
     */
    public boolean current(String stationId, double lat, double lon) {
        Terrain t = byStation.get(stationId);
        return t != null && Geo.distanceKm(t.lat(), t.lon(), lat, lon) < 0.1;
    }
}
