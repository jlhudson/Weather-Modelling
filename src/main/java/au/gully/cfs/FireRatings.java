package au.gully.cfs;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
import au.gully.upstreams.Ledger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The official fire danger rating per fire ban district (W-23), as the CFS publishes it for today and the
 * next three days: the AFDRS rating, its Fire Behaviour Index and the total fire ban. Published beats
 * derived: this is what the public were told, and it stands beside the indices computed here.
 * <p>
 * Read when asked and held {@link #LIFE}, never on a clock (W-15). Outside the fire danger season the CFS
 * stops publishing and the feed keeps the last day it did - in September 2026, "No Rating" for 1 May - so
 * every day carries its date, and a day before today is never passed off as today's.
 */
@Slf4j
@Component
public class FireRatings {

    public static final String URL = "https://cfs.geohub.sa.gov.au/server/rest/services/CFS_Custodial_Read/"
            + "South_Australia_Fire_Danger_Ratings_Read/FeatureServer/0/query?where=1%3D1&outFields=*&returnGeometry=false&f=json";
    public static final Duration LIFE = Duration.ofHours(1);
    static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");

    private final HttpFetcher http;
    private final Ledger ledger;
    private final boolean enabled;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile Map<String, DistrictRating> byDistrict = Map.of();
    private volatile Instant readAt;
    private volatile Instant triedAt;
    private volatile String failure;

    public FireRatings(HttpFetcher http, Ledger ledger, au.gully.platform.GullyProperties properties) {
        this.http = http;
        this.ledger = ledger;
        this.enabled = properties.enabled();
    }

    /**
     * One district's published days.
     *
     * @param number the CFS's district number
     * @param aac    the Bureau's code for its fire weather district ({@code SA_FW015}), which its warnings name
     */
    public record DistrictRating(String district, int number, String aac, List<RatingDay> days) {

        /**
         * The published day for a date, if the feed carries one.
         */
        public Optional<RatingDay> on(LocalDate date) {
            return days.stream().filter(d -> date.equals(d.date())).findFirst();
        }

        /**
         * The last day the feed carries: what was last published, however old.
         */
        public Optional<RatingDay> latest() {
            return days.stream().filter(d -> d.date() != null).max(java.util.Comparator.comparing(RatingDay::date));
        }
    }

    /**
     * One published day for one district.
     *
     * @param rating the AFDRS word: {@code No Rating}, {@code Moderate}, {@code High}, {@code Extreme}, {@code Catastrophic}
     * @param fbi    the Fire Behaviour Index the rating was drawn from
     */
    public record RatingDay(LocalDate date, String rating, Integer fbi, boolean totalFireBan) {
    }

    /**
     * Every district's days, read now when none are held or they are older than {@link #LIFE}.
     */
    public synchronized Map<String, DistrictRating> ensure(Instant now) {
        boolean due = readAt == null || Duration.between(readAt, now).compareTo(LIFE) >= 0;
        boolean resting = triedAt != null && failure != null && Duration.between(triedAt, now).compareTo(FireDistricts.RETRY) < 0;
        if (enabled && due && !resting) {
            triedAt = now;
            long started = System.nanoTime();
            try {
                Fetched f = http.get(URI.create(URL));
                Map<String, DistrictRating> fresh = parse(f.body());
                if (fresh.isEmpty()) {
                    throw new UpstreamException("the ratings feed carried no districts");
                }
                byDistrict = fresh;
                readAt = now;
                failure = null;
                ledger.record(FireDistricts.ID, 0, true, Duration.ofNanos(System.nanoTime() - started), "fire danger ratings, " + fresh.size() + " districts");
            } catch (UpstreamException | RuntimeException e) {
                failure = e.getMessage();
                ledger.record(FireDistricts.ID, 0, false, Duration.ofNanos(System.nanoTime() - started), "fire danger ratings: " + e.getMessage());
                log.warn("cfs: the fire danger ratings could not be read: {}", e.getMessage());
            }
        }
        return byDistrict;
    }

    Map<String, DistrictRating> parse(byte[] json) {
        Map<String, DistrictRating> out = new LinkedHashMap<>();
        JsonNode features = Nodes.at(mapper.readTree(json), "features");
        if (features == null || !features.isArray()) {
            return out;
        }
        for (JsonNode f : features) {
            JsonNode a = Nodes.at(f, "attributes");
            String district = Nodes.str(a, "firebandistrict");
            if (district == null) {
                continue;
            }
            // Days 0 to 4 as published; the feed repeats today as day 0 and day 1, so a date is kept once.
            Map<LocalDate, RatingDay> days = new LinkedHashMap<>();
            for (int day = 0; day <= 4; day++) {
                String rating = Nodes.str(a, "firedangerrating_" + day);
                Instant from = iso(Nodes.str(a, "sttmutc" + day));
                if (rating == null || from == null) {
                    continue;
                }
                LocalDate date = from.atZone(ADELAIDE).toLocalDate();
                Double fbi = Nodes.parse(Nodes.str(a, "fbi" + day));
                days.putIfAbsent(date, new RatingDay(date, rating.trim(), fbi == null ? null : fbi.intValue(), "YES".equalsIgnoreCase(Nodes.str(a, "tfb" + day))));
            }
            Double number = Nodes.parse(Nodes.str(a, "distnumb"));
            out.put(key(district), new DistrictRating(district.trim(), number == null ? 0 : number.intValue(), Nodes.str(a, "aac"), new ArrayList<>(days.values())));
        }
        return out;
    }

    static String key(String district) {
        return district == null ? null : district.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    private static Instant iso(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return s.endsWith("Z") ? Instant.parse(s) : OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * One district's days, reading the feed if due.
     */
    public Optional<DistrictRating> of(String district) {
        return Optional.ofNullable(district == null ? null : ensure(Instant.now()).get(key(district)));
    }

    public Instant readAt() {
        return readAt;
    }

    public String failure() {
        return failure;
    }
}
