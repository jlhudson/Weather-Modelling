package au.gully.cfs;

import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Grass curing per fire ban district — how dry the grass is, as a percentage — entered on the console
 * each week in fire season with the date it was entered (docs/06 item 18). No open API publishes it:
 * the CFS GeoHub was checked on 18 September 2026 and carries no curing layer, so the console entry is
 * the whole source. Carried on every active hexagon in the district; a hexagon with no curing figure
 * has no grassland index.
 * <p>
 * The fifteen districts are the ones the ratings feed and the district shapes name, so the page can
 * offer every row before a figure has been typed for it.
 */
@Slf4j
@Service
public class Curing {

    public static final List<String> DISTRICTS = List.of(
            "ADELAIDE METROPOLITAN", "EASTERN EYRE PENINSULA", "FLINDERS", "KANGAROO ISLAND", "LOWER EYRE PENINSULA",
            "LOWER SOUTH EAST", "MID NORTH", "MOUNT LOFTY RANGES", "MURRAYLANDS", "NORTH EAST PASTORAL",
            "NORTH WEST PASTORAL", "RIVERLAND", "UPPER SOUTH EAST", "WEST COAST", "YORKE PENINSULA");

    private final JdbcClient db;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final List<Consumer<Instant>> listeners = new ArrayList<>();

    public Curing(JdbcClient db) {
        this.db = db;
    }

    public void onUpdate(Consumer<Instant> listener) {
        listeners.add(listener);
    }

    public void rehydrate() {
        entries.clear();
        db.sql("select district, percent, entered_on, source, updated_by, updated_at from grass_curing").query().listOfRows()
                .forEach(row -> entries.put((String) row.get("district"), new Entry((String) row.get("district"),
                        Db.integer(row.get("percent")), Db.date(row.get("entered_on")), (String) row.get("source"),
                        (String) row.get("updated_by"), Db.instant(row.get("updated_at")))));
        log.info("curing: {} districts have a figure", entries.size());
    }

    public Optional<Entry> forDistrict(String district) {
        String key = Ratings.normalise(district);
        return Optional.ofNullable(key == null ? null : entries.get(key));
    }

    /**
     * Every district, with its figure where one is held, for the console page.
     */
    public List<Entry> all() {
        Set<String> names = new TreeSet<>(DISTRICTS);
        names.addAll(entries.keySet());
        List<Entry> out = new ArrayList<>();
        for (String n : names) {
            out.add(entries.getOrDefault(n, new Entry(n, null, null, null, null, null)));
        }
        return out;
    }

    @Transactional
    public Entry save(String district, int percent, LocalDate enteredOn, String source, String by) {
        String key = Ratings.normalise(district);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a curing figure belongs to a district");
        }
        int pct = Math.max(0, Math.min(100, percent));
        LocalDate on = enteredOn == null ? LocalDate.now() : enteredOn;
        Instant now = Instant.now();
        db.sql("""
                insert into grass_curing (district, percent, entered_on, source, updated_by, updated_at)
                values (:d, :p, :on, :src, :by, :at)
                on conflict (district) do update set percent = excluded.percent, entered_on = excluded.entered_on,
                  source = excluded.source, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("d", key).param("p", pct).param("on", on).param("src", source == null || source.isBlank() ? null : source.trim())
                .param("by", by).param("at", Db.ts(now)).update();
        Entry e = new Entry(key, pct, on, source, by, now);
        entries.put(key, e);
        log.info("curing: {} set to {}% (entered {}) by {}", key, pct, on, by);
        listeners.forEach(l -> l.accept(now));
        return e;
    }

    @Transactional
    public void clear(String district, String by) {
        String key = Ratings.normalise(district);
        if (key != null && entries.remove(key) != null) {
            db.sql("delete from grass_curing where district = :d").param("d", key).update();
            log.info("curing: {} cleared by {}", key, by);
            listeners.forEach(l -> l.accept(Instant.now()));
        }
    }

    /**
     * One district's figure. {@code percent} is null on a district nothing has been entered for.
     */
    public record Entry(String district, Integer percent, LocalDate enteredOn, String source, String updatedBy, Instant updatedAt) {
    }
}
