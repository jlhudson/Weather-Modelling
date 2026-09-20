package au.gully.drought;

import au.gully.storage.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The days a hexagon's drought was stepped with (W-19): one row per hexagon per day - the rain and
 * the day's maximum, and where they came from - written as the drought reads them and read back
 * before any source is asked, so the archive is fetched once for a hexagon and never again, and
 * the series behind a drought factor can be seen. The one thing kept per hexagon: rain is a
 * place's, not a station's. Kept five years.
 */
@Repository
public class DroughtDays {

    private final JdbcClient db;

    public DroughtDays(JdbcClient db) {
        this.db = db;
    }

    /**
     * One day's inputs, and which source supplied them: {@code stations}, {@code archive} or {@code recent}.
     */
    public record Day(LocalDate day, double rainMm, double maxTemperatureC, String source) {
    }

    /**
     * The days held for a hexagon in a range, by date.
     */
    public SortedMap<LocalDate, Day> of(String hexagonId, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, Day> out = new TreeMap<>();
        db.sql("select day, rain_mm, max_temperature_c, source from drought_day where hexagon_id = :h and day >= :from and day <= :to order by day")
                .param("h", hexagonId).param("from", from).param("to", to).query().listOfRows()
                .forEach(row -> {
                    LocalDate d = Db.date(row.get("day"));
                    out.put(d, new Day(d, Db.dbl(row.get("rain_mm")), Db.dbl(row.get("max_temperature_c")), (String) row.get("source")));
                });
        return out;
    }

    /**
     * The last {@code days} days held for a hexagon, oldest first, for the API.
     */
    public List<Day> recent(String hexagonId, int days) {
        return db.sql("select day, rain_mm, max_temperature_c, source from drought_day where hexagon_id = :h order by day desc limit :n")
                .param("h", hexagonId).param("n", Math.max(1, Math.min(days, 2000))).query().listOfRows().stream()
                .map(row -> new Day(Db.date(row.get("day")), Db.dbl(row.get("rain_mm")), Db.dbl(row.get("max_temperature_c")), (String) row.get("source")))
                .sorted(java.util.Comparator.comparing(Day::day)).toList();
    }

    /**
     * Days written; a day already held keeps what it had, since the first source to supply a day is
     * the one the drought was stepped with.
     */
    public int save(String hexagonId, Iterable<Day> days, Instant now) {
        int n = 0;
        for (Day d : days) {
            n += db.sql("insert into drought_day (hexagon_id, day, rain_mm, max_temperature_c, source, written_at) values (:h, :day, :rain, :max, :source, :at)"
                            + " on conflict (hexagon_id, day) do nothing")
                    .param("h", hexagonId).param("day", d.day()).param("rain", d.rainMm()).param("max", d.maxTemperatureC())
                    .param("source", d.source()).param("at", Db.ts(now)).update();
        }
        return n;
    }

    public long count(String hexagonId) {
        Long n = db.sql("select count(*) from drought_day where hexagon_id = :h").param("h", hexagonId).query(Long.class).single();
        return n == null ? 0 : n;
    }

    /**
     * The row counts by source, for the status.
     */
    public Map<String, Long> bySource() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        db.sql("select source, count(*) as n from drought_day group by source order by source").query().listOfRows()
                .forEach(row -> out.put((String) row.get("source"), ((Number) row.get("n")).longValue()));
        return out;
    }
}
