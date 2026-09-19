package au.gully.cfs;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.ReadOutcome;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The official fire danger rating per fire ban district, as the CFS publishes it on its GeoHub for
 * today and the next four days: the AFDRS rating, its Fire Behaviour Index and the total fire ban
 * flag (docs/06 item 4). Read hourly, without the district geometry (32 KB against 6.4 MB), and put
 * on every reading beside the indices computed here. Published beats derived: this is the number the
 * public were told, and it sits beside ours so a reader sees both.
 * <p>
 * South Australia only, because that is who publishes it this way. A hexagon in another state carries
 * no official block rather than a guessed one.
 */
@Slf4j
@Component
public class Ratings {

    public static final Duration EVERY = Duration.ofHours(1);
    public static final String URL = "https://cfs.geohub.sa.gov.au/server/rest/services/CFS_Custodial_Read/"
            + "South_Australia_Fire_Danger_Ratings_Read/FeatureServer/0/query?where=1%3D1&outFields=*&returnGeometry=false&f=json";

    private static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");

    private final HttpFetcher http;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, DistrictRating> byDistrict = new ConcurrentHashMap<>();
    private final List<Consumer<Instant>> listeners = new ArrayList<>();
    private volatile Instant readAt;
    private volatile Instant checkedAt;
    private volatile String failure;

    public Ratings(HttpFetcher http) {
        this.http = http;
    }

    public void onUpdate(Consumer<Instant> listener) {
        listeners.add(listener);
    }

    public static String normalise(String district) {
        return district == null ? null : district.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /**
     * The feed, read now if it has not been checked inside {@link #EVERY} (W-14). Called from an ask
     * for a South Australian hexagon; one thread reads at a time.
     */
    public synchronized ReadOutcome ensure(Instant now) {
        if (checkedAt != null && Duration.between(checkedAt, now).compareTo(EVERY) < 0) {
            return ReadOutcome.SKIPPED;
        }
        checkedAt = now;
        poll();
        return failure == null ? ReadOutcome.READ : ReadOutcome.FAILED;
    }

    public int poll() {
        Instant now = Instant.now();
        try {
            Fetched f = http.get(URI.create(URL));
            Map<String, DistrictRating> fresh = parse(f.body(), now);
            if (fresh.isEmpty()) {
                throw new UpstreamException("the ratings feed carried no districts");
            }
            byDistrict.clear();
            byDistrict.putAll(fresh);
            if (readAt == null) {
                log.info("cfs ratings: {} districts, today {}", fresh.size(), fresh.values().stream()
                        .map(d -> d.district() + " " + d.at(now).map(RatingDay::rating).orElse("-")).sorted().toList());
            }
            readAt = now;
            failure = null;
            listeners.forEach(l -> l.accept(now));
        } catch (UpstreamException | RuntimeException e) {
            if (failure == null) {
                log.warn("cfs ratings: {}", e.getMessage());
            }
            failure = e.getMessage();
        }
        return byDistrict.size();
    }

    Map<String, DistrictRating> parse(byte[] json, Instant now) {
        Map<String, DistrictRating> out = new LinkedHashMap<>();
        JsonNode root = mapper.readTree(json);
        JsonNode features = Nodes.at(root, "features");
        if (features == null || !features.isArray()) {
            return out;
        }
        for (JsonNode f : features) {
            JsonNode a = Nodes.at(f, "attributes");
            String district = Nodes.str(a, "firebandistrict");
            if (district == null) {
                continue;
            }
            List<RatingDay> days = new ArrayList<>();
            for (int day = 0; day <= 4; day++) {
                String rating = Nodes.str(a, "firedangerrating_" + day);
                if (rating == null) {
                    continue;
                }
                Instant from = iso(Nodes.str(a, "sttmutc" + day));
                Instant to = iso(Nodes.str(a, "endtmutc" + day));
                LocalDate date = from == null ? null : from.atZone(ADELAIDE).toLocalDate();
                Double fbi = Nodes.parse(Nodes.str(a, "fbi" + day));
                days.add(new RatingDay(day, date, rating, fbi == null ? null : fbi.intValue(),
                        "YES".equalsIgnoreCase(Nodes.str(a, "tfb" + day)), from, to));
            }
            Double number = Nodes.parse(Nodes.str(a, "distnumb"));
            out.put(normalise(district), new DistrictRating(district, number == null ? 0 : number.intValue(),
                    Nodes.str(a, "aac"), days, now));
        }
        return out;
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

    public Optional<DistrictRating> rating(String district) {
        return Optional.ofNullable(district == null ? null : byDistrict.get(normalise(district)));
    }

    public Collection<DistrictRating> all() {
        return byDistrict.values().stream().sorted(Comparator.comparing(DistrictRating::district)).toList();
    }

    public Instant readAt() {
        return readAt;
    }

    /** When an ask last checked the feed; null if never. */
    public Instant checkedAt() {
        return checkedAt;
    }

    public String failure() {
        return failure;
    }

    /**
     * The five days for one district.
     *
     * @param aac the Bureau's code for the district ({@code SA_FW015}), which the feed carries
     */
    public record DistrictRating(String district, int number, String aac, List<RatingDay> days, Instant readAt) {

        public DistrictRating {
            days = days == null ? List.of() : List.copyOf(days);
        }

        /**
         * The day covering an instant, else day 0.
         */
        public Optional<RatingDay> at(Instant now) {
            for (RatingDay d : days) {
                if (d.from() != null && d.to() != null && !now.isBefore(d.from()) && now.isBefore(d.to())) {
                    return Optional.of(d);
                }
            }
            return days.isEmpty() ? Optional.empty() : Optional.of(days.getFirst());
        }
    }

    /**
     * One day of the published rating for one district.
     *
     * @param rating the Bureau's word: {@code No Rating}, {@code Moderate}, {@code High}, {@code Extreme}, {@code Catastrophic}
     * @param fbi    the AFDRS fire behaviour index the rating was drawn from
     */
    public record RatingDay(int day, LocalDate date, String rating, Integer fbi, boolean totalFireBan, Instant from, Instant to) {
    }
}
