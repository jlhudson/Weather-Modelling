package au.weather.service;

import au.weather.geo.Geo;
import au.weather.core.WeatherReport;
import au.weather.json.Json;
import au.weather.terrain.ElevationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cache with three axes: time, distance, and height.
 * <p>
 * A weather reading is not a value keyed by a point, it is a value that is <em>approximately true over
 * a region for a while</em>, and a cache keyed on exact coordinates would miss every time - two
 * incidents in the same suburb have different coordinates and identical weather. So the key is
 * proximity: the first request in an area creates an anchor and every request that lands within
 * {@code reachKm} of it before {@code ttl} expires is answered from it, without an upstream call.
 * <p>
 * <strong>Height is the third axis because distance alone gets the Hills wrong.</strong> Two points can
 * be twenty kilometres apart on the map and four hundred metres apart vertically, and they do not share
 * a temperature, a humidity or a wind. A flat radius wide enough to be useful across the plains is
 * necessarily wide enough to serve the escarpment from the suburb below it. So proximity is measured as
 * a single {@link Geo#reachMetres reach}, with a weight that states how much horizontal distance a metre
 * of height is worth - and a weight of zero turns the whole vertical term off without removing it.
 * <p>
 * <strong>The reach is deliberately tighter than the model grid, not looser.</strong> Over Australia the
 * finest model available is about 15 km, so a fifteen-kilometre reach stands in for roughly one grid
 * cell rather than four. That costs upstream calls, and it is affordable only because the free allowance
 * is an order of magnitude larger than this system's appetite for it; {@code WeatherGovernor} is what
 * widens the reach again on the day that stops being true.
 * <p>
 * Bounded by activity, not by area (docs/09 9.2): a few dozen anchors on a normal day, a few hundred on
 * a bad one, none at all over empty country.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherCache {

    /**
     * How many missing terrain heights one sweep will pay an upstream lookup for. Five a sweep drains
     * the five-hundred-anchor ceiling in about seven hours, and the realistic backlog after a deploy is
     * a handful. Small on purpose: the sweep also expires anchors, and that should never wait behind a
     * round trip.
     */
    private static final int TERRAIN_BACKFILL_PER_SWEEP = 5;

    /**
     * How often the free, no-network half of the terrain backfill may walk the anchors.
     */
    private static final Duration FREE_BACKFILL_EVERY = Duration.ofMinutes(1);

    private final WeatherRepositories.WeatherAnchorRepository anchors;
    private final WeatherProperties properties;
    private final WeatherGovernor governor;
    private final ElevationService elevation;
    private final Json json;
    private final Map<UUID, Anchor> live = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    /**
     * Nanotime of the last free terrain pass, so the scan stays off the per-lookup path.
     */
    private final AtomicLong lastFreeBackfill = new AtomicLong(System.nanoTime() - FREE_BACKFILL_EVERY.toNanos());

    /**
     * The ranking itself, with nothing around it: no Spring, no JPA, no clock of its own.
     * <p>
     * Static and package-private so it can be tested against hand-built anchors. This repository has no
     * mocking framework, so extracting the decision is not a stylistic preference, it is the only way the
     * rule that decides which weather an incident gets can have a unit test at all - the same reason
     * {@code PagerSocketGovernor.targets} is static.
     */
    static Optional<Hit> best(Collection<Anchor> anchors, double lat, double lon, Double queryTerrainM,
                              double reachMetres, Duration maxAge, double verticalWeight,
                              Tier tier, Instant now) {
        return anchors.stream()
                .map(a -> hit(a, lat, lon, queryTerrainM, verticalWeight, tier, now))
                .filter(h -> h.reachMetres() <= reachMetres)
                .filter(h -> h.age().compareTo(maxAge) <= 0)
                // Nearest, then freshest. The tie-break is not cosmetic: a forced refetch creates a
                // second anchor at exactly the point of the first, and without it the old one can keep
                // winning the tie forever - so "force a fresh call" would appear to do nothing.
                .min(Comparator.comparingDouble(Hit::reachMetres).thenComparing(Hit::age));
    }

    /**
     * One candidate measured. The vertical term applies only when both heights are known; a candidate
     * missing one is compared horizontally rather than excluded, because a missing height must never be
     * able to mean no weather.
     */
    private static Hit hit(Anchor a, double lat, double lon, Double queryTerrainM,
                           double verticalWeight, Tier tier, Instant now) {
        double horizontal = Geo.planarMetres(lat, lon, a.lat(), a.lon());
        Double delta = queryTerrainM == null || a.terrainM() == null ? null : queryTerrainM - a.terrainM();
        double reach = delta == null ? horizontal : Geo.reachMetres(horizontal, delta, verticalWeight);
        return new Hit(a, horizontal, reach, delta, Duration.between(a.fetchedAt(), now), tier);
    }

    /**
     * {@code OptionalDouble} to a nullable {@code Double}, because null and sea level are different facts.
     */
    private static Double boxed(OptionalDouble v) {
        return v.isPresent() ? v.getAsDouble() : null;
    }

    /**
     * Phase 2: anchors written before the restart are still good, and re-fetching them costs allowance.
     * <p>
     * An anchor stored without a height (terrain off, the upstream refused, the sea) rehydrates with a null
     * one and is compared horizontally until something fills it in. Deliberately <em>not</em> backfilled here:
     * this runs in the rehydrate startup phase, which is documented as touching no network, and five hundred
     * anchors each asking for a height would be a flagrant breach of that. {@link #backfillTerrain} does it
     * later, a few at a time, on a phase where the network is allowed.
     */
    public void rehydrate() {
        live.clear();
        Instant since = Instant.now().minus(properties.cache().retainFor());
        int withoutTerrain = 0;
        for (WeatherAnchorEntity row : anchors.findByFetchedAtAfter(since)) {
            try {
                WeatherReport report = json.read(row.getPayload(), WeatherReport.class);
                live.put(row.getId(), new Anchor(row.getId(), row.getLat(), row.getLon(), report,
                        row.getFetchedAt(), row.getExpiresAt(), row.getTerrainM(), new AtomicInteger(row.getHits())));
                if (row.getTerrainM() == null) {
                    withoutTerrain++;
                }
            } catch (RuntimeException e) {
                log.debug("discarding unreadable weather anchor {}: {}", row.getId(), e.getMessage());
            }
        }
        log.info("weather cache rehydrated: {} anchors within {}{}", live.size(), properties.cache().retainFor(),
                withoutTerrain == 0 ? "" : ", " + withoutTerrain + " without a terrain height yet");
    }

    /**
     * Both answers this cache can give for a point, in one walk: the one it is happy to serve, and the one
     * it would fall back to if every upstream refused.
     * <p>
     * <strong>Green beats yellow however far away it is.</strong> A reading taken twenty minutes ago
     * eighteen kilometres away is a better answer than one taken two hours ago next door, because weather
     * moves and the cache's own time-to-live is the statement of how fast. Within a tier, nearest wins.
     * <p>
     * <strong>The two tiers do not search the same ground.</strong> Green is bounded by the tuned reach,
     * because that is the promise the cache makes about what a reading may stand in for. The last resort is
     * bounded by nothing but the sweep: when Open-Meteo and Google have both refused, a reading from a long
     * way off that says how far off it is beats no weather at all. The sweep already drops anything past
     * max-stale, so the oldest thing findable here is three hours old whatever the distance.
     * <p>
     * Resolves the query point's height <strong>once</strong>, from heights already held, and never calls
     * out: this sits in the lookup path and a fetch here would put a network round trip in front of every
     * cache hit. When the height is not held every candidate is compared horizontally, which is the old
     * behaviour, and {@link Selection#note} says so rather than leaving it to be inferred.
     */
    public Selection find(double lat, double lon) {
        Instant now = Instant.now();
        freeTerrainBackfill();
        // One snapshot for the whole walk. Reading the reach and the time-to-live separately would let an
        // hourly step land between them and pair a new reach with an old clock.
        WeatherTuning t = governor.tuning();
        Double queryTerrain = elevation.enabled() ? boxed(elevation.cached(lat, lon)) : null;
        String note = queryTerrain != null ? null
                : elevation.enabled() ? "no terrain height held for this point yet; compared horizontally"
                : "terrain is disabled; compared horizontally";

        Collection<Anchor> candidates = live.values();
        Optional<Hit> green = best(candidates, lat, lon, queryTerrain, t.anchorReachMetres(),
                t.ttl(), t.verticalWeight(), Tier.GREEN, now);
        // No reach limit on the fallback: this is only ever consulted once every provider has refused.
        Optional<Hit> yellow = best(candidates, lat, lon, queryTerrain, Double.MAX_VALUE,
                properties.cache().maxStale(), t.verticalWeight(), Tier.YELLOW, now);
        return new Selection(green, yellow, queryTerrain, note);
    }

    /**
     * Stores a freshly fetched report as a new anchor at the point that was actually asked about.
     * <p>
     * The terrain height is sampled <strong>before</strong> {@link #persist} rather than inside it, and that
     * is the whole reason this is two methods. {@link ElevationService#at} may call out, and a network
     * round trip taken while holding one of ten pooled connections is how a slow upstream turns into a
     * database outage. The caller has just made an upstream call anyway, so the cost lands where it is
     * already expected.
     */
    public Anchor store(double lat, double lon, WeatherReport report) {
        return persist(lat, lon, report, boxed(elevation.at(lat, lon)));
    }

    @Transactional
    Anchor persist(double lat, double lon, WeatherReport report, Double terrainM) {
        Instant now = Instant.now();
        Instant expires = report.expiresAt() == null ? now.plus(governor.tuning().ttl()) : report.expiresAt();
        WeatherAnchorEntity row = new WeatherAnchorEntity();
        row.setId(UUID.randomUUID());
        row.setLat(lat);
        row.setLon(lon);
        row.setGeom(Geo.point(lat, lon));
        row.setProvider(report.provider());
        row.setModel(report.model());
        row.setPayload(json.write(report));
        row.setFetchedAt(now);
        row.setExpiresAt(expires);
        row.setTerrainM(terrainM);
        row.setHits(0);
        anchors.save(row);
        Anchor anchor = new Anchor(row.getId(), lat, lon, report, now, expires, terrainM, new AtomicInteger());
        live.put(anchor.id(), anchor);
        evictIfCrowded();
        return anchor;
    }

    /**
     * The free half of the backfill: heights for anchors the memo already holds.
     * <p>
     * Never calls out, so it costs a map lookup per anchor. It does <em>not</em> follow that it can run on
     * every lookup: an anchor the memo cannot answer for keeps a null height, so an unthrottled pass
     * re-asks about all five hundred of them on every single weather request and never converges. Once a
     * minute drains a realistic backlog just as fast - the backlog only appears after a rehydrate - and
     * takes the scan off the hot path. The write back to the row is left to {@link #touch} and
     * {@link #backfillTerrain}.
     */
    private void freeTerrainBackfill() {
        if (!elevation.enabled()) {
            return;
        }
        long now = System.nanoTime();
        long last = lastFreeBackfill.get();
        if (now - last < FREE_BACKFILL_EVERY.toNanos() || !lastFreeBackfill.compareAndSet(last, now)) {
            return;
        }
        for (Anchor a : live.values()) {
            if (a.terrainM() != null) {
                continue;
            }
            Double height = boxed(elevation.cached(a.lat(), a.lon()));
            if (height != null) {
                live.put(a.id(), new Anchor(a.id(), a.lat(), a.lon(), a.report(), a.fetchedAt(),
                        a.expiresAt(), height, a.hits()));
            }
        }
    }

    /**
     * Fills in terrain heights for anchors that rehydrated without one, a few at a time.
     * <p>
     * Called from {@link #sweep}, which runs on the sweeper's thread five minutes after boot and every
     * five minutes after that - a phase where a lookup that calls out is allowed, unlike rehydrate.
     * Bounded per pass because the point is to drain a backlog quietly over an hour or two, not to stall
     * a sweep behind five hundred round trips.
     * <p>
     * An anchor the elevation service cannot answer for stays null and is retried on the next pass. That
     * is deliberate: the alternative is a marker meaning "asked and failed", which would have to be
     * invalidated whenever the upstream recovered, and the retry is one cheap lookup.
     *
     * @return how many heights were filled in
     */
    @Transactional
    public int backfillTerrain(int max) {
        if (!elevation.enabled() || max <= 0) {
            return 0;
        }
        int filled = 0;
        for (Anchor a : live.values()) {
            if (filled >= max) {
                break;
            }
            if (a.terrainM() != null) {
                continue;
            }
            Double height = boxed(elevation.at(a.lat(), a.lon()));
            if (height == null) {
                continue;
            }
            live.put(a.id(), new Anchor(a.id(), a.lat(), a.lon(), a.report(), a.fetchedAt(),
                    a.expiresAt(), height, a.hits()));
            anchors.findById(a.id()).ifPresent(row -> {
                row.setTerrainM(height);
                anchors.save(row);
            });
            filled++;
        }
        if (filled > 0) {
            log.debug("weather cache: filled in {} terrain heights", filled);
        }
        return filled;
    }

    /**
     * Drops anchors nothing can use any more, from memory and from the table.
     */
    @Transactional
    public int sweep() {
        Instant now = Instant.now();
        backfillTerrain(TERRAIN_BACKFILL_PER_SWEEP);
        Duration keep = properties.cache().maxStale();
        List<UUID> gone = live.values().stream()
                .filter(a -> Duration.between(a.fetchedAt(), now).compareTo(keep) > 0)
                .map(Anchor::id)
                .toList();
        gone.forEach(live::remove);
        anchors.deleteByFetchedAtBefore(now.minus(properties.cache().retainFor()));
        return gone.size();
    }

    /**
     * The ceiling on live anchors, enforced by dropping the least used first. An anchor that has never
     * been reused was a one-off lookup; an anchor with forty hits is a fire ground.
     */
    private void evictIfCrowded() {
        int max = properties.cache().maxAnchors();
        if (live.size() <= max) {
            return;
        }
        live.values().stream()
                .sorted(Comparator.comparingInt((Anchor a) -> a.hits().get()).thenComparing(Anchor::fetchedAt))
                .limit(live.size() - (long) max)
                .map(Anchor::id)
                .toList()
                .forEach(live::remove);
    }

    /**
     * Records that an anchor answered a request. Saves explicitly rather than relying on dirty
     * checking: this is called from {@link #find} inside the same bean, so the proxy that would have
     * started a transaction is bypassed and there is nothing to flush the change.
     */
    void touch(Anchor anchor) {
        int count = anchor.hits().incrementAndGet();
        anchors.findById(anchor.id()).ifPresent(row -> {
            row.setHits(count);
            row.setLastHitAt(Instant.now());
            // A height the free backfill resolved in memory rides along on the next hit, so the row catches
            // up without a write of its own.
            if (row.getTerrainM() == null && anchor.terrainM() != null) {
                row.setTerrainM(anchor.terrainM());
            }
            anchors.save(row);
        });
    }

    public List<Anchor> all() {
        return live.values().stream().sorted(Comparator.comparing(Anchor::fetchedAt).reversed()).toList();
    }

    public int size() {
        return live.size();
    }

    /**
     * The bookkeeping is done by the caller at the moment of use, not by {@link #find}, and that is a
     * deliberate correction rather than a refactor.
     * <p>
     * One walk now returns both tiers, so counting a hit inside it would count the yellow candidate that
     * was found and then never served - inflating the hit rate with answers nobody received. Three methods,
     * three call sites, one each.
     */
    public void serveGreen(Hit hit) {
        hits.incrementAndGet();
        touch(hit.anchor());
    }

    /**
     * A degraded answer was served. Counted apart from a hit, because it is not one.
     */
    public void serveYellow(Hit hit) {
        stale.incrementAndGet();
        touch(hit.anchor());
    }

    /**
     * Nothing in the cache could answer, so an upstream was asked.
     */
    public void missed() {
        misses.incrementAndGet();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public long staleCount() {
        return stale.get();
    }

    /**
     * Share of requests answered without an upstream call, which is the number this whole design exists to raise.
     */
    public double hitRate() {
        long total = hits.get() + misses.get() + stale.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }

    /**
     * Share of requests answered with a reading the cache had already stopped being happy about. Zero on a
     * healthy day; anything else is the number that says an upstream has been refusing, and nothing in the
     * console could show it before.
     */
    public double staleRate() {
        long total = hits.get() + misses.get() + stale.get();
        return total == 0 ? 0 : (double) stale.get() / total;
    }

    /**
     * Which of the two answers a hit is, and therefore what the caller is allowed to do with it.
     */
    public enum Tier {
        /**
         * Inside the time-to-live and inside the reach. Served without asking anyone.
         */
        GREEN,
        /**
         * Older or further, served only because every upstream refused. Its age travels with it.
         */
        YELLOW
    }

    /**
     * @param terrainM true ground height under the anchor, or null when terrain is off or no height has
     *                 been resolved yet. A null is compared horizontally rather than excluded, so a missing
     *                 height can never mean no weather
     */
    public record Anchor(UUID id, double lat, double lon, WeatherReport report, Instant fetchedAt,
                         Instant expiresAt, Double terrainM, AtomicInteger hits) {
    }

    /**
     * One candidate, measured on every axis the decision used.
     *
     * @param distanceMetres       ground distance, which is what a person pictures
     * @param reachMetres          the three-dimensional separation that actually decided it; equal to
     *                             {@code distanceMetres} on flat ground, or wherever a height was missing
     * @param elevationDeltaMetres query point minus anchor, or null when either height was unknown and
     *                             the comparison fell back to horizontal
     */
    public record Hit(Anchor anchor, double distanceMetres, double reachMetres, Double elevationDeltaMetres,
                      Duration age, Tier tier) {
    }

    /**
     * What one walk of the cache found. Both tiers are returned rather than one, because the choice
     * between them depends on whether an upstream answered, and that is the service's decision to make,
     * not the cache's.
     *
     * @param queryTerrainM the height resolved for the query point, or null when none was held for it
     * @param note          why the comparison was horizontal, or null when the vertical term applied
     */
    public record Selection(Optional<Hit> green, Optional<Hit> yellow, Double queryTerrainM, String note) {
    }
}
