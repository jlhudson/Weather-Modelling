package au.gully.cfs;

import au.gully.storage.Db;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fire danger ledger (W-45): every district-day the CFS ratings feed has shown, kept for ever, so an
 * incident can be read against the danger the public was told on its day. Written from each read of the
 * feed ({@link FireRatings#ensure}); a day is first seen as a forecast and then, when it comes, as the
 * rating of the day, and the row keeps the newest reading and says whether the day was ever read as
 * the feed's first day.
 * <p>
 * <b>{@code No Rating} is a rating</b> and is kept as one. A day with no row is a day the feed was not
 * read - nobody asked, or the CFS did not answer - and must never be read as no rating.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FireDangerDays {

    private final JdbcClient db;

    /**
     * One district's day as held.
     *
     * @param district  the district as {@link FireRatings#key} writes it, capitals: {@code MOUNT LOFTY RANGES}
     * @param name      as the feed spells it, when this service read it; null for a day brought over from The Hub
     * @param published whether the day was ever the feed's first day: the rating of the day, not a forecast of it
     */
    public record Day(String district, String name, Integer number, LocalDate date, String rating, Integer fbi,
                      boolean totalFireBan, boolean published, Instant firstSeenAt, Instant lastSeenAt) {
    }

    /**
     * Every day of every district in one read, upserted. A day already held takes the newer reading and
     * keeps {@code published} once set; an older read never overwrites a newer one.
     */
    public int record(Map<String, FireRatings.DistrictRating> read, Instant at) {
        int written = 0;
        for (FireRatings.DistrictRating d : read.values()) {
            List<FireRatings.RatingDay> days = d.days();
            for (int i = 0; i < days.size(); i++) {
                FireRatings.RatingDay day = days.get(i);
                if (day.date() == null || day.rating() == null) {
                    continue;
                }
                written += db.sql("""
                                insert into fire_danger_day (district, rating_date, name, number, rating, fbi, total_fire_ban, published, first_seen_at, last_seen_at)
                                values (:district, :date, :name, :number, :rating, :fbi, :tfb, :published, :at, :at)
                                on conflict (district, rating_date) do update set name = excluded.name, number = excluded.number,
                                    rating = excluded.rating, fbi = excluded.fbi, total_fire_ban = excluded.total_fire_ban,
                                    published = fire_danger_day.published or excluded.published, last_seen_at = excluded.last_seen_at
                                where fire_danger_day.last_seen_at < excluded.last_seen_at
                                """)
                        .param("district", FireRatings.key(d.district())).param("date", day.date()).param("name", d.district())
                        .param("number", d.number()).param("rating", day.rating()).param("fbi", day.fbi())
                        .param("tfb", day.totalFireBan()).param("published", i == 0).param("at", Db.ts(at))
                        .update();
            }
        }
        return written;
    }

    /**
     * Every district's days from {@code from} to {@code to}, both included, by district number and date.
     */
    public List<Day> between(LocalDate from, LocalDate to) {
        return db.sql("""
                        select district, name, number, rating_date, rating, fbi, total_fire_ban, published, first_seen_at, last_seen_at
                        from fire_danger_day where rating_date between :from and :to order by number nulls last, district, rating_date
                        """)
                .param("from", from).param("to", to)
                .query((rs, n) -> new Day(rs.getString("district"), rs.getString("name"), Db.integer(rs.getObject("number")),
                        Db.date(rs.getObject("rating_date")), rs.getString("rating"), Db.integer(rs.getObject("fbi")),
                        rs.getBoolean("total_fire_ban"), rs.getBoolean("published"), Db.instant(rs.getObject("first_seen_at")),
                        Db.instant(rs.getObject("last_seen_at"))))
                .list();
    }

    public long size() {
        return db.sql("select count(*) from fire_danger_day").query(Long.class).single();
    }

    /**
     * The days grouped by district, in the shape {@code /api/v1/fire-danger} answers.
     */
    static List<Map<String, Object>> byDistrict(List<Day> days) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Day d : days) {
            Map<String, Object> district = out.computeIfAbsent(d.district(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("district", d.district());
                m.put("name", d.name() == null ? FireBan.titleCase(d.district()) : d.name());
                m.put("number", d.number());
                m.put("days", new ArrayList<Map<String, Object>>());
                return m;
            });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.date().toString());
            m.put("rating", d.rating());
            m.put("fbi", d.fbi());
            m.put("totalFireBan", d.totalFireBan());
            m.put("published", d.published());
            m.put("firstSeenAt", d.firstSeenAt() == null ? null : d.firstSeenAt().toString());
            m.put("lastSeenAt", d.lastSeenAt() == null ? null : d.lastSeenAt().toString());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) district.get("days");
            list.add(m);
        }
        return new ArrayList<>(out.values());
    }
}
