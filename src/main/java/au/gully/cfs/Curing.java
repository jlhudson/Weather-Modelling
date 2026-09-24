package au.gully.cfs;

import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grass curing per fire ban district (W-24) - how dry the grass is, as a percentage - with the fuel load
 * the grass indices are drawn for, entered on the console each week in fire season with the date it
 * describes. No open feed publishes it (the CFS GeoHub was checked in September 2026 and carries no curing
 * layer), so the console is the whole source. A district with no figure has no grass index; a figure
 * older than {@link #OLD} is still used, and said to be old.
 */
@Slf4j
@Service
public class Curing {

    /**
     * The fifteen districts, so the page offers every row before a figure has been entered for it.
     */
    public static final List<String> DISTRICTS = List.of(
            "Adelaide Metropolitan", "Eastern Eyre Peninsula", "Flinders", "Kangaroo Island", "Lower Eyre Peninsula",
            "Lower South East", "Mid North", "Mount Lofty Ranges", "Murraylands", "North East Pastoral",
            "North West Pastoral", "Riverland", "Upper South East", "West Coast", "Yorke Peninsula");
    /**
     * McArthur drew the grassland meter for this load: the default where nothing better is known.
     */
    public static final double STANDARD_LOAD_T_HA = 4.5;
    /**
     * The CFS's map is weekly: a figure older than a fortnight has missed one.
     */
    public static final Duration OLD = Duration.ofDays(14);

    private final JdbcClient db;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Curing(JdbcClient db) {
        this.db = db;
    }

    /**
     * One district's figure; {@code percent} null where nothing has been entered.
     */
    public record Entry(String district, Integer percent, double fuelLoadTHa, LocalDate enteredOn, String source, String updatedBy, Instant updatedAt) {

        public boolean old(LocalDate today) {
            return enteredOn != null && enteredOn.plusDays(OLD.toDays()).isBefore(today);
        }
    }

    public void rehydrate() {
        entries.clear();
        db.sql("select district, percent, fuel_load_t_ha, entered_on, source, updated_by, updated_at from grass_curing").query().listOfRows()
                .forEach(row -> {
                    String d = (String) row.get("district");
                    entries.put(FireRatings.key(d), new Entry(d, Db.integer(row.get("percent")), Db.dbl(row.get("fuel_load_t_ha")),
                            Db.date(row.get("entered_on")), (String) row.get("source"), (String) row.get("updated_by"), Db.instant(row.get("updated_at"))));
                });
        log.info("curing: {} districts have a figure", entries.size());
    }

    public Optional<Entry> of(String district) {
        String key = FireRatings.key(district);
        return Optional.ofNullable(key == null ? null : entries.get(key)).filter(e -> e.percent() != null);
    }

    /**
     * Every district, with its figure where one is held.
     */
    public List<Entry> all() {
        Set<String> names = new TreeSet<>(DISTRICTS);
        entries.values().forEach(e -> {
            names.removeIf(n -> FireRatings.key(n).equals(FireRatings.key(e.district())));
            names.add(e.district());
        });
        List<Entry> out = new ArrayList<>();
        for (String n : names) {
            out.add(entries.getOrDefault(FireRatings.key(n), new Entry(n, null, STANDARD_LOAD_T_HA, null, null, null, null)));
        }
        return out;
    }

    public Entry save(String district, int percent, Double fuelLoadTHa, LocalDate enteredOn, String source, String by) {
        if (district == null || district.isBlank()) {
            throw new IllegalArgumentException("a curing figure belongs to a district");
        }
        String name = district.trim();
        int pct = Math.max(0, Math.min(100, percent));
        double load = fuelLoadTHa == null || fuelLoadTHa <= 0 ? STANDARD_LOAD_T_HA : Math.min(25, fuelLoadTHa);
        LocalDate on = enteredOn == null ? LocalDate.now(FireRatings.ADELAIDE) : enteredOn;
        Instant now = Instant.now();
        String src = source == null || source.isBlank() ? null : source.trim();
        db.sql("""
                insert into grass_curing (district, percent, fuel_load_t_ha, entered_on, source, updated_by, updated_at)
                values (:d, :p, :w, :on, :src, :by, :at)
                on conflict (district) do update set percent = excluded.percent, fuel_load_t_ha = excluded.fuel_load_t_ha,
                  entered_on = excluded.entered_on, source = excluded.source, updated_by = excluded.updated_by, updated_at = excluded.updated_at""")
                .param("d", name).param("p", pct).param("w", load).param("on", on).param("src", src).param("by", by).param("at", Db.ts(now)).update();
        Entry e = new Entry(name, pct, load, on, src, by, now);
        entries.put(FireRatings.key(name), e);
        log.info("curing: {} set to {}% at {} t/ha (for {}) by {}", name, pct, load, on, by);
        return e;
    }

    public void clear(String district, String by) {
        String key = FireRatings.key(district);
        Entry e = key == null ? null : entries.remove(key);
        if (e != null) {
            db.sql("delete from grass_curing where district = :d").param("d", e.district()).update();
            log.info("curing: {} cleared by {}", e.district(), by);
        }
    }
}
