package au.gully.hexagons;

import au.gully.platform.Json;
import au.gully.science.Conditions;
import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the weather <em>was</em> (docs/06 item 2): a snapshot of a hexagon's current conditions and
 * fire picture — never the forecast — taken when The Hub asks about the hexagon and says an incident
 * is present, at most once every {@link #CURRENT_FOR}. Nothing is ever deleted from the table.
 * <p>
 * A Bureau station updating every ten minutes never writes history on its own; its latest values
 * are simply kept. Only hexagons that had an incident have history.
 */
@Slf4j
@Repository
public class History {

    /**
     * How long a snapshot counts as current: a hexagon asked about at 06:00 and again at 12:00 has
     * two entries, one asked about repeatedly inside three hours has one.
     */
    public static final Duration CURRENT_FOR = Duration.ofHours(3);

    private final JdbcClient db;
    private final Json json;

    public History(JdbcClient db, Json json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Writes a snapshot, unless one inside {@link #CURRENT_FOR} already stands for this hexagon.
     *
     * @return whether one was written
     */
    public boolean snapshot(Hexagon h, FirePictures.Now now, String incident, Instant askedAt) {
        if (h.lastSnapshotAt() != null && Duration.between(h.lastSnapshotAt(), askedAt).compareTo(CURRENT_FOR) < 0) {
            return false;
        }
        Snapshot s = new Snapshot(h.id(), now.at(), askedAt, incident, now.conditions(), now.from(),
                h.stationId() != null ? h.stationId() : h.nearestStationId(), h.fire(),
                h.drought() == null ? null : h.drought().index(), h.forecast() == null ? null : h.forecast().upstream());
        db.sql("insert into reading_snapshot (hexagon_id, at, asked_at, incident, payload) values (:h, :at, :asked, :inc, :p::jsonb)")
                .param("h", h.id()).param("at", Db.ts(now.at())).param("asked", Db.ts(askedAt))
                .param("inc", incident == null ? null : incident.substring(0, Math.min(128, incident.length())))
                .param("p", json.write(s)).update();
        return true;
    }

    /**
     * The snapshot nearest an instant for a hexagon, or empty when it has none.
     */
    public Optional<Snapshot> nearest(String hexagonId, Instant at) {
        return db.sql("select payload from reading_snapshot where hexagon_id = :h"
                        + " order by abs(extract(epoch from (at - :at))) limit 1")
                .param("h", hexagonId).param("at", Db.ts(at))
                .query().listOfRows().stream().findFirst()
                .map(row -> json.read(row.get("payload").toString(), Snapshot.class));
    }

    /**
     * Every snapshot a hexagon has, newest first, at most {@code limit}.
     */
    public List<Snapshot> of(String hexagonId, int limit) {
        return db.sql("select payload from reading_snapshot where hexagon_id = :h order by at desc limit :n")
                .param("h", hexagonId).param("n", Math.max(1, Math.min(limit, 1000)))
                .query().listOfRows().stream()
                .map(row -> json.read(row.get("payload").toString(), Snapshot.class)).toList();
    }

    /**
     * The latest snapshot per hexagon at or before an instant, for the map's time slider.
     */
    public Map<String, Snapshot> allAt(Instant at) {
        Map<String, Snapshot> out = new java.util.HashMap<>();
        db.sql("select distinct on (hexagon_id) hexagon_id, payload from reading_snapshot where at <= :at"
                        + " order by hexagon_id, at desc")
                .param("at", Db.ts(at)).query().listOfRows()
                .forEach(row -> out.put((String) row.get("hexagon_id"), json.read(row.get("payload").toString(), Snapshot.class)));
        return out;
    }

    public long count() {
        Long n = db.sql("select count(*) from reading_snapshot").query(Long.class).single();
        return n == null ? 0 : n;
    }

    public long countFor(String hexagonId) {
        Long n = db.sql("select count(*) from reading_snapshot where hexagon_id = :h").param("h", hexagonId).query(Long.class).single();
        return n == null ? 0 : n;
    }

    /**
     * The rows since an instant, as maps, for the nightly export.
     */
    public List<Map<String, Object>> rowsSince(Instant since) {
        return db.sql("select id, hexagon_id, at, asked_at, incident, payload from reading_snapshot where asked_at >= :since order by id")
                .param("since", Db.ts(since)).query().listOfRows();
    }

    /**
     * One reading as it was: the conditions, where they came from, and the picture computed from them.
     *
     * @param at the time the conditions describe — the snapshot's own time, which the API reports
     */
    public record Snapshot(String hexagonId, Instant at, Instant askedAt, String incident, Conditions current,
                           String currentFrom, String stationId, FirePicture fire,
                           au.gully.science.DroughtIndex drought, String upstream) {
    }
}
