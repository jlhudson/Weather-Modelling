package au.gully.drought;

import au.gully.hexagons.Cell;
import au.gully.hexagons.Grid;
import au.gully.hexagons.RiverState;
import au.gully.platform.Json;
import au.gully.storage.Db;
import au.gully.upstreams.OpenMeteo;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GloFAS river discharge, keyed on the river model's own smaller cells — 5 km, because a river is a
 * line and a reading reused across a 15 km hexagon would confidently report a different watercourse.
 * One fetch per river cell per day, held in memory and in {@code river_discharge}.
 */
@Slf4j
@Service
public class Rivers {

    public static final Grid RIVER_GRID = new Grid(5);
    static final int BASELINE_DAYS = 92;
    static final int FORECAST_DAYS = 7;

    private final Upstreams upstreams;
    private final JdbcClient db;
    private final Json json;
    private final Map<String, RiverState> cells = new ConcurrentHashMap<>();

    public Rivers(Upstreams upstreams, JdbcClient db, Json json) {
        this.upstreams = upstreams;
        this.db = db;
        this.json = json;
    }

    public void rehydrate() {
        cells.clear();
        db.sql("select id, lat, lon, computed_for, has_river, series from river_discharge where computed_for >= :from")
                .param("from", LocalDate.now().minusDays(2)).query().listOfRows()
                .forEach(row -> {
                    try {
                        RiverState s = new RiverState((String) row.get("id"), Db.dbl(row.get("lat")), Db.dbl(row.get("lon")),
                                Db.date(row.get("computed_for")), Boolean.TRUE.equals(row.get("has_river")),
                                json.read(row.get("series").toString(), Series.class).rows());
                        cells.put(s.cellId(), s);
                    } catch (RuntimeException e) {
                        log.debug("river cell {} could not be read back: {}", row.get("id"), e.getMessage());
                    }
                });
        log.info("river cells rehydrated: {}", cells.size());
    }

    /**
     * The river cell a point falls in, fetched for today if it is not already held for today.
     */
    public Optional<RiverState> at(double lat, double lon, LocalDate today) {
        Cell cell = RIVER_GRID.cellOf(lat, lon);
        RiverState held = cells.get(cell.id());
        if (held != null && !held.computedFor().isBefore(today)) {
            return Optional.of(held);
        }
        Optional<List<OpenMeteo.DischargeRow>> rows = upstreams.discharge(cell.lat(), cell.lon(), BASELINE_DAYS, FORECAST_DAYS);
        if (rows.isEmpty()) {
            return Optional.ofNullable(held);
        }
        boolean hasRiver = rows.get().stream().anyMatch(r -> r.cumecs() != null);
        RiverState state = new RiverState(cell.id(), cell.lat(), cell.lon(), today, hasRiver, rows.get());
        cells.put(cell.id(), state);
        db.sql("""
                insert into river_discharge (id, lat, lon, computed_for, has_river, series, updated_at)
                values (:id, :lat, :lon, :for, :has, :series::jsonb, :at)
                on conflict (id) do update set computed_for = excluded.computed_for, has_river = excluded.has_river,
                  series = excluded.series, updated_at = excluded.updated_at""")
                .param("id", cell.id()).param("lat", cell.lat()).param("lon", cell.lon()).param("for", today)
                .param("has", hasRiver).param("series", json.write(new Series(rows.get()))).param("at", Db.ts(Instant.now()))
                .update();
        return Optional.of(state);
    }

    public int size() {
        return cells.size();
    }

    /**
     * The series as a JSON object rather than a bare array, so the column reads back through one type.
     */
    record Series(List<OpenMeteo.DischargeRow> rows) {
    }
}
