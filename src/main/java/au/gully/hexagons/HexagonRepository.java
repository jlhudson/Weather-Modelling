package au.gully.hexagons;

import au.gully.platform.Json;
import au.gully.science.LandUse;
import au.gully.storage.Db;
import au.gully.upstreams.Forecast;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code hexagon} table: one row per hexagon the service holds, written when the hexagon is
 * created and when what it holds is replaced — never on a served hit (docs/06 item 0). Read whole at
 * start to rebuild the in-memory store.
 */
@Slf4j
@Repository
public class HexagonRepository {

    private final JdbcClient db;
    private final Json json;

    public HexagonRepository(JdbcClient db, Json json) {
        this.db = db;
        this.json = json;
    }

    /**
     * The grid the hexagon-keyed tables were written with, against the grid the code has. A change to
     * the width or the anchor ({@code Grid}'s constants) changes every hexagon's id, so the hexagons,
     * the history and the river cells are reset — they are rebuilt from the sources
     * as they are asked about; the history is the one real loss, and re-gridding accepts it. Called
     * before anything is rehydrated.
     *
     * @return whether the tables were reset
     */
    public boolean ensureGrid(Grid grid) {
        String spec = grid.spec();
        String held = db.sql("select spec from grid_spec where id = 1").query(String.class).optional().orElse(null);
        if (held == null) {
            db.sql("insert into grid_spec (id, spec, since) values (1, :spec, :at)").param("spec", spec).param("at", Db.ts(Instant.now())).update();
            log.info("grid: {}", spec);
            return false;
        }
        if (held.equals(spec)) {
            return false;
        }
        long hexagons = count("hexagon"), snapshots = count("reading_snapshot"), rivers = count("river_discharge");
        db.sql("truncate table hexagon, reading_snapshot, river_discharge").update();
        db.sql("update grid_spec set spec = :spec, since = :at where id = 1").param("spec", spec).param("at", Db.ts(Instant.now())).update();
        log.warn("grid changed from [{}] to [{}]: every hexagon id changed, so {} hexagons, {} snapshots and {} river cells were reset",
                held, spec, hexagons, snapshots, rivers);
        return true;
    }

    private long count(String table) {
        Long n = db.sql("select count(*) from " + table).query(Long.class).single();
        return n == null ? 0 : n;
    }

    public void insert(Hexagon h) {
        db.sql("""
                insert into hexagon (id, q, r, lat, lon, zone, elevation_m, elevation_from, slope_deg, land_use, fire_ban_district,
                  bureau_district, station_id, nearest_station_id, nearest_station_km, created_at, activated_at, last_asked_at)
                values (:id, :q, :r, :lat, :lon, :zone, :elev, :elevFrom, :slope, :land::jsonb, :fbd, :bd, :station, :nearest, :nearestKm,
                  :created, :activated, :asked)
                on conflict (id) do update set zone = excluded.zone, elevation_m = excluded.elevation_m,
                  elevation_from = excluded.elevation_from, slope_deg = excluded.slope_deg, land_use = excluded.land_use,
                  fire_ban_district = excluded.fire_ban_district, bureau_district = excluded.bureau_district,
                  station_id = excluded.station_id, nearest_station_id = excluded.nearest_station_id,
                  nearest_station_km = excluded.nearest_station_km""")
                .param("id", h.id()).param("q", h.cell().q()).param("r", h.cell().r())
                .param("lat", h.cell().lat()).param("lon", h.cell().lon()).param("zone", h.zone())
                .param("elev", h.elevationM()).param("elevFrom", h.elevationFrom()).param("slope", h.slopeDeg())
                .param("land", h.landUse() == null ? null : json.write(h.landUse()))
                .param("fbd", h.fireBanDistrict()).param("bd", h.bureauDistrict()).param("station", h.stationId())
                .param("nearest", h.nearestStationId()).param("nearestKm", h.nearestStationKm())
                .param("created", Db.ts(h.createdAt())).param("activated", Db.ts(h.activatedAt()))
                .param("asked", Db.ts(h.lastAskedAt()))
                .update();
    }

    /**
     * @param expiresAt the end of the forecast's life under the cap in force when it was fetched, for
     *                  the row's own readability; the store reads the cap live
     */
    public void saveForecast(Hexagon h, Instant expiresAt) {
        Forecast f = h.forecast();
        db.sql("""
                update hexagon set zone = :zone, elevation_m = :elev, elevation_from = :elevFrom, upstream = :upstream,
                  forecast = :forecast::jsonb, forecast_fetched_at = :fetched, current_expires_at = :cur, forecast_expires_at = :fx,
                  activated_at = :activated, last_asked_at = :asked
                where id = :id""")
                .param("id", h.id()).param("zone", h.zone()).param("elev", h.elevationM()).param("elevFrom", h.elevationFrom())
                .param("upstream", f == null ? null : f.upstream())
                .param("forecast", f == null ? null : json.write(f))
                .param("fetched", Db.ts(f == null ? null : f.fetchedAt()))
                .param("cur", Db.ts((Instant) null))
                .param("fx", Db.ts(f == null ? null : expiresAt))
                .param("activated", Db.ts(h.activatedAt())).param("asked", Db.ts(h.lastAskedAt()))
                .update();
    }

    public void saveActivity(Hexagon h) {
        db.sql("update hexagon set activated_at = :activated, last_asked_at = :asked, last_snapshot_at = :snap where id = :id")
                .param("id", h.id()).param("activated", Db.ts(h.activatedAt())).param("asked", Db.ts(h.lastAskedAt()))
                .param("snap", Db.ts(h.lastSnapshotAt())).update();
    }

    public void saveDrought(Hexagon h) {
        DroughtState d = h.drought();
        db.sql("update hexagon set drought = :d::jsonb, drought_computed_for = :for where id = :id")
                .param("id", h.id()).param("d", d == null ? null : json.write(d))
                .param("for", d == null ? null : d.computedFor()).update();
    }

    public void saveRiver(Hexagon h) {
        RiverState r = h.river();
        db.sql("update hexagon set river = :r::jsonb, river_computed_for = :for where id = :id")
                .param("id", h.id()).param("r", r == null ? null : json.write(r))
                .param("for", r == null ? null : r.computedFor()).update();
    }

    public void saveDistrict(Hexagon h) {
        db.sql("update hexagon set fire_ban_district = :d where id = :id").param("id", h.id()).param("d", h.fireBanDistrict()).update();
    }

    public void saveStations(Hexagon h) {
        db.sql("update hexagon set station_id = :s, nearest_station_id = :n, nearest_station_km = :km, bureau_district = :bd where id = :id")
                .param("id", h.id()).param("s", h.stationId()).param("n", h.nearestStationId())
                .param("km", h.nearestStationKm()).param("bd", h.bureauDistrict()).update();
    }

    /**
     * Every row, as hexagons without a fire picture (that is computed once the registries are up).
     */
    public List<Hexagon> loadAll(Grid grid) {
        List<Hexagon> out = new ArrayList<>();
        for (Map<String, Object> row : db.sql("select * from hexagon").query().listOfRows()) {
            try {
                out.add(from(row, grid));
            } catch (RuntimeException e) {
                log.warn("hexagon {} could not be read back: {}", row.get("id"), e.getMessage());
            }
        }
        return out;
    }

    private Hexagon from(Map<String, Object> row, Grid grid) {
        Cell cell = grid.cell(Db.integer(row.get("q")), Db.integer(row.get("r")));
        String land = text(row.get("land_use"));
        String forecast = text(row.get("forecast"));
        String drought = text(row.get("drought"));
        String river = text(row.get("river"));
        return new Hexagon(cell, (String) row.get("zone"), Db.dbl(row.get("elevation_m")), (String) row.get("elevation_from"),
                Db.dbl(row.get("slope_deg")), land == null ? null : json.read(land, LandUse.class),
                (String) row.get("fire_ban_district"), (String) row.get("bureau_district"), (String) row.get("station_id"),
                (String) row.get("nearest_station_id"), Db.dbl(row.get("nearest_station_km")),
                forecast == null ? null : json.read(forecast, Forecast.class),
                drought == null ? null : json.read(drought, DroughtState.class),
                river == null ? null : json.read(river, RiverState.class),
                null, Db.instant(row.get("created_at")), Db.instant(row.get("activated_at")),
                Db.instant(row.get("last_asked_at")), Db.instant(row.get("last_snapshot_at")), 0, 0);
    }

    private static String text(Object column) {
        return column == null ? null : column.toString();
    }

    public long count() {
        Long n = db.sql("select count(*) from hexagon").query(Long.class).single();
        return n == null ? 0 : n;
    }
}
