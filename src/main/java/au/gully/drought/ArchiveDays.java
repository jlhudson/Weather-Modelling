package au.gully.drought;

import au.gully.storage.Db;
import au.gully.upstreams.OpenMeteo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The archive as fetched (W-21): every rain day Open-Meteo returned for a point, raw, five years.
 * Read before the archive is asked and written whole when it answers, so a point is fetched once;
 * and the record a drought is recomputed from when the rule that picks its stations changes. Keyed
 * on the point asked for - the hexagon's centre, to four decimals as the URL carries it - not on
 * the hexagon, which is what the user asked: the rain a place had, apart from any hexagon's use of it.
 */
@Repository
public class ArchiveDays {

    private final JdbcClient db;

    public ArchiveDays(JdbcClient db) {
        this.db = db;
    }

    /**
     * The days held for a point in a range, by date, with the source that supplied each.
     */
    public SortedMap<LocalDate, DroughtDays.Day> of(double lat, double lon, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, DroughtDays.Day> out = new TreeMap<>();
        db.sql("select day, rain_mm, max_temperature_c, source from archive_day where lat = :lat and lon = :lon and day >= :from and day <= :to order by day")
                .param("lat", key(lat)).param("lon", key(lon)).param("from", from).param("to", to).query().listOfRows()
                .forEach(row -> {
                    LocalDate d = Db.date(row.get("day"));
                    out.put(d, new DroughtDays.Day(d, Db.dbl(row.get("rain_mm")), Db.dbl(row.get("max_temperature_c")), (String) row.get("source")));
                });
        return out;
    }

    /**
     * An answer kept whole. The archive's day replaces a recent-days row for the same day, since the
     * reanalysis is the better figure; otherwise a day held stays.
     */
    public int save(double lat, double lon, List<OpenMeteo.DailyRow> rows, String source, Instant now) {
        int n = 0;
        for (OpenMeteo.DailyRow r : rows) {
            if (r.date() == null || r.rainMm() == null || r.maxTemperatureC() == null) {
                continue;
            }
            n += db.sql("""
                    insert into archive_day (lat, lon, day, rain_mm, max_temperature_c, source, fetched_at)
                    values (:lat, :lon, :day, :rain, :max, :source, :at)
                    on conflict (lat, lon, day) do update set rain_mm = excluded.rain_mm, max_temperature_c = excluded.max_temperature_c,
                      source = excluded.source, fetched_at = excluded.fetched_at
                    where archive_day.source = 'recent' and excluded.source = 'archive'""")
                    .param("lat", key(lat)).param("lon", key(lon)).param("day", r.date()).param("rain", r.rainMm()).param("max", r.maxTemperatureC())
                    .param("source", source).param("at", Db.ts(now)).update();
        }
        return n;
    }

    public long count() {
        Long n = db.sql("select count(*) from archive_day").query(Long.class).single();
        return n == null ? 0 : n;
    }

    public int prune(LocalDate before) {
        return db.sql("delete from archive_day where day < :day").param("day", before).update();
    }

    /**
     * The point as the URL carries it: four decimals, about eleven metres.
     */
    static double key(double v) {
        return Math.round(v * 1e4) / 1e4;
    }
}
