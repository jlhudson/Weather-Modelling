package au.gully.reading;

import au.gully.platform.Json;
import au.gully.record.Record;
import au.gully.storage.Db;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The readings kept for a caller's reference (W-27). An ask that names what it is for - The Hub sends an incident's
 * id as {@code ref} - has its reading kept, at most once every {@link #EVERY} a reference, and a later ask for a past
 * moment of that reference is answered with the reading kept nearest it, within {@link #WITHIN}. Kept as the record
 * is, {@link Record#KEEP}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Snapshots {

    public static final Duration EVERY = Duration.ofHours(3);
    public static final Duration WITHIN = Duration.ofHours(3);

    private final JdbcClient db;
    private final Json json;

    /**
     * The reading kept for a reference, unless one was kept inside {@link #EVERY}.
     *
     * @return whether it was kept
     */
    public boolean keep(String ref, double lat, double lon, Instant at, Map<String, Object> reading) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        Integer recent = db.sql("select count(*) from reading_snapshot where ref = :ref and at > :since")
                .param("ref", ref.trim()).param("since", Db.ts(at.minus(EVERY))).query(Integer.class).single();
        if (recent != null && recent > 0) {
            return false;
        }
        db.sql("insert into reading_snapshot (ref, lat, lon, at, body) values (:ref, :lat, :lon, :at, cast(:body as jsonb))")
                .param("ref", ref.trim()).param("lat", lat).param("lon", lon).param("at", Db.ts(at)).param("body", json.write(reading)).update();
        return true;
    }

    /**
     * The reading kept for a reference nearest a moment, within {@link #WITHIN}; with {@code keptAt} put on it.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> near(String ref, Instant at) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return db.sql("""
                        select at, body::text as body from reading_snapshot where ref = :ref and at between :from and :to
                        order by abs(extract(epoch from at - :at)) limit 1""")
                .param("ref", ref.trim()).param("from", Db.ts(at.minus(WITHIN))).param("to", Db.ts(at.plus(WITHIN))).param("at", Db.ts(at))
                .query().listOfRows().stream().findFirst().map(row -> {
                    Map<String, Object> body = new LinkedHashMap<>(json.read((String) row.get("body"), Map.class));
                    body.put("keptAt", Db.instant(row.get("at")).toString());
                    return body;
                });
    }

    public int prune(Instant now) {
        return db.sql("delete from reading_snapshot where at < :before").param("before", Db.ts(now.minus(Record.KEEP))).update();
    }
}
