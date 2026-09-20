package au.gully.storage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code setting} table (V9): what the console sets and a restart must keep, one row per value,
 * the value as text and the reader knowing its shape. The station reach ({@code Reach}, W-18) and the
 * drought's rule ({@code DroughtRule}, W-22) live here; a deployment's properties do not.
 */
@Repository
public class ConsoleSettings {

    private final JdbcClient db;

    public ConsoleSettings(JdbcClient db) {
        this.db = db;
    }

    /**
     * One value as held: what, who set it, when.
     */
    public record Setting(String value, String by, Instant at) {
    }

    public Optional<Setting> read(String key) {
        return db.sql("select value, updated_by, updated_at from setting where key = :key").param("key", key)
                .query().listOfRows().stream().findFirst()
                .map(row -> new Setting(String.valueOf(row.get("value")), (String) row.get("updated_by"), Db.instant(row.get("updated_at"))));
    }

    public void write(String key, String value, String by, Instant at) {
        db.sql("""
                insert into setting (key, value, updated_by, updated_at) values (:key, :value, :by, :at)
                on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("key", key).param("value", value).param("by", by).param("at", Db.ts(at)).update();
    }

    /**
     * Every setting, for the status.
     */
    public Map<String, Setting> all() {
        Map<String, Setting> out = new java.util.LinkedHashMap<>();
        db.sql("select key, value, updated_by, updated_at from setting order by key").query().listOfRows()
                .forEach(row -> out.put((String) row.get("key"), new Setting(String.valueOf(row.get("value")), (String) row.get("updated_by"), Db.instant(row.get("updated_at")))));
        return out;
    }
}
