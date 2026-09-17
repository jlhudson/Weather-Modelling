package au.weather.service;

import au.weather.core.WeatherReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides which weather an incident is given.
 * <p>
 * Against the static ranker rather than the component, because there is no mocking framework here and this
 * is the one piece of the weather feature where a wrong answer is silent: an anchor from the wrong side of
 * the escarpment produces a plausible temperature, a plausible humidity and a fire index built on both.
 */
class WeatherCacheSelectionTest {

    private static final Instant NOW = Instant.parse("2026-09-07T05:00:00Z");
    private static final double WEIGHT = 67;
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration MAX_STALE = Duration.ofHours(3);

    /**
     * Adelaide, near enough. One degree of latitude is about 111 km, so 0.09 is roughly 10 km.
     */
    private static final double LAT = -34.93;
    private static final double LON = 138.60;

    private static WeatherCache.Anchor anchor(String name, double lat, double lon, Double terrainM, Duration age) {
        WeatherReport report = new WeatherReport(lat, lon, 46.0, "Australia/Adelaide", "open-meteo",
                name, "test", NOW.minus(age), null, null, List.of(), List.of());
        return new WeatherCache.Anchor(UUID.nameUUIDFromBytes(name.getBytes()), lat, lon, report,
                NOW.minus(age), NOW.plus(TTL), terrainM, new AtomicInteger());
    }

    private static Optional<WeatherCache.Hit> pick(List<WeatherCache.Anchor> anchors, Double queryTerrain,
                                                   double reachMetres, Duration maxAge, double weight) {
        return WeatherCache.best(anchors, LAT, LON, queryTerrain, reachMetres, maxAge, weight,
                WeatherCache.Tier.GREEN, NOW);
    }

    /**
     * The case the whole change exists for. On the flat, the anchor 3 km away wins. Put it 400 m up the
     * escarpment and the one 12 km across the plain wins instead, because that is where the weather
     * actually is.
     */
    @Test
    void anAnchorUpTheEscarpmentLosesToOneFurtherAcrossThePlain() {
        WeatherCache.Anchor hills = anchor("hills", LAT + 0.027, LON, 412.0, Duration.ofMinutes(5));
        WeatherCache.Anchor plain = anchor("plain", LAT + 0.108, LON, 50.0, Duration.ofMinutes(5));
        List<WeatherCache.Anchor> both = List.of(hills, plain);

        // Flat: the near one wins, which is the old behaviour and must still hold where terrain is flat.
        assertThat(pick(both, 45.0, 30_000, TTL, 0).orElseThrow().anchor().report().model()).isEqualTo("hills");

        // Weighted: asking from 45 m on the plain, the hills anchor is 367 m up and is now much further.
        WeatherCache.Hit chosen = pick(both, 45.0, 30_000, TTL, WEIGHT).orElseThrow();
        assertThat(chosen.anchor().report().model()).isEqualTo("plain");
        assertThat(chosen.elevationDeltaMetres()).isNotNull();
    }

    /**
     * A weight of zero is the switch the feature ships behind. It must reproduce the old rule exactly.
     */
    @Test
    void aZeroWeightRanksIdenticallyToPlainDistance() {
        List<WeatherCache.Anchor> anchors = List.of(
                anchor("near-high", LAT + 0.027, LON, 900.0, Duration.ofMinutes(5)),
                anchor("far-low", LAT + 0.108, LON, 10.0, Duration.ofMinutes(5)));
        WeatherCache.Hit hit = pick(anchors, 20.0, 30_000, TTL, 0).orElseThrow();
        assertThat(hit.anchor().report().model()).isEqualTo("near-high");
        assertThat(hit.reachMetres()).isEqualTo(hit.distanceMetres());
    }

    /**
     * A missing height must never mean no weather. An anchor with no terrain value is compared
     * horizontally and reports a null delta, so a reader can see why it was not penalised.
     */
    @Test
    void anAnchorWithNoTerrainHeightIsComparedHorizontally() {
        WeatherCache.Anchor unknown = anchor("unknown", LAT + 0.027, LON, null, Duration.ofMinutes(5));
        WeatherCache.Hit hit = pick(List.of(unknown), 45.0, 30_000, TTL, WEIGHT).orElseThrow();
        assertThat(hit.elevationDeltaMetres()).isNull();
        assertThat(hit.reachMetres()).isEqualTo(hit.distanceMetres());
    }

    /**
     * And the same when it is the query point whose tile is missing: every candidate degrades together.
     */
    @Test
    void anUnknownQueryHeightDegradesEveryCandidateConsistently() {
        List<WeatherCache.Anchor> anchors = List.of(
                anchor("near-high", LAT + 0.027, LON, 900.0, Duration.ofMinutes(5)),
                anchor("far-low", LAT + 0.108, LON, 10.0, Duration.ofMinutes(5)));
        WeatherCache.Hit hit = pick(anchors, null, 30_000, TTL, WEIGHT).orElseThrow();
        assertThat(hit.anchor().report().model()).isEqualTo("near-high");
        assertThat(hit.elevationDeltaMetres()).isNull();
    }

    /**
     * Inside the reach in two dimensions, outside it in three. It must not be served.
     */
    @Test
    void aCandidateInsideTheReachFlatButOutsideItInThreeDimensionsIsExcluded() {
        WeatherCache.Anchor ridge = anchor("ridge", LAT + 0.027, LON, 500.0, Duration.ofMinutes(5));
        assertThat(pick(List.of(ridge), 45.0, 15_000, TTL, 0)).isPresent();
        assertThat(pick(List.of(ridge), 45.0, 15_000, TTL, WEIGHT)).isEmpty();
    }

    /**
     * Green beats yellow however much closer yellow is. Ranked here as two separate tier searches, which
     * is what the service does: it only ever looks at the yellow answer once every provider has refused.
     */
    @Test
    void aFreshAnchorFurtherAwayBeatsAStaleOneNextDoor() {
        WeatherCache.Anchor stale = anchor("stale-near", LAT + 0.001, LON, 45.0, Duration.ofHours(2));
        WeatherCache.Anchor fresh = anchor("fresh-far", LAT + 0.108, LON, 45.0, Duration.ofMinutes(5));
        List<WeatherCache.Anchor> both = List.of(stale, fresh);

        assertThat(pick(both, 45.0, 30_000, TTL, WEIGHT).orElseThrow().anchor().report().model())
                .isEqualTo("fresh-far");
        // The stale one is only reachable through the wider age gate, which is the last-resort search.
        assertThat(WeatherCache.best(both, LAT, LON, 45.0, Double.MAX_VALUE, MAX_STALE, WEIGHT,
                WeatherCache.Tier.YELLOW, NOW).orElseThrow().anchor().report().model()).isEqualTo("stale-near");
    }

    /**
     * The last resort has no distance limit at all: at that point any labelled reading beats none.
     */
    @Test
    void theLastResortSearchIsNotBoundedByReach() {
        WeatherCache.Anchor faraway = anchor("far", LAT + 1.8, LON, 45.0, Duration.ofHours(2));
        assertThat(pick(List.of(faraway), 45.0, 20_000, TTL, WEIGHT)).isEmpty();
        WeatherCache.Hit hit = WeatherCache.best(List.of(faraway), LAT, LON, 45.0, Double.MAX_VALUE,
                MAX_STALE, WEIGHT, WeatherCache.Tier.YELLOW, NOW).orElseThrow();
        assertThat(hit.distanceMetres()).isGreaterThan(150_000);
        assertThat(hit.tier()).isEqualTo(WeatherCache.Tier.YELLOW);
    }

    /**
     * The tie-break that makes the console's force button appear to work. A forced refetch stores a second
     * anchor at exactly the first's coordinates; without freshest-as-tie-break the old one wins forever.
     */
    @Test
    void anIdenticallyPlacedRefetchWinsTheTieOnAge() {
        WeatherCache.Anchor old = anchor("old", LAT, LON, 45.0, Duration.ofMinutes(20));
        WeatherCache.Anchor fresh = anchor("forced", LAT, LON, 45.0, Duration.ZERO);
        assertThat(pick(List.of(old, fresh), 45.0, 30_000, TTL, WEIGHT).orElseThrow().anchor().report().model())
                .isEqualTo("forced");
    }

    @Test
    void anEmptyCacheSelectsNothingRatherThanThrowing() {
        assertThat(pick(List.of(), 45.0, 30_000, TTL, WEIGHT)).isEmpty();
    }
}
