package au.weather.api;

import au.weather.geo.Geo;
import au.weather.core.*;
import au.weather.geojson.Coverage;
import au.weather.geojson.GeoJson;
import au.weather.service.*;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static au.weather.core.Numbers.percent1;
import static au.weather.core.Numbers.round1;

/**
 * The Weather layer: one point, one answer, and an honest account of where it came from.
 * <p>
 * The provenance block is not decoration. An answer served from an anchor 22 km away and 40 minutes
 * old is a different fact from one fetched at the point a moment ago, and a consumer that cannot tell
 * them apart will treat the first as the second - which is precisely the failure mode docs/13 is
 * about, because stale looks identical to current unless something says otherwise.
 * <p>
 * {@link #coverage} is the same honesty drawn instead of written. The cache is the whole cost model of
 * this feature and its two levers - how far a reading may be stretched and how long it stays good -
 * are tuned blind unless someone can see the footprint they actually produce.
 */
@Service
@RequiredArgsConstructor
public class WeatherLayer {

    public static final String DISCLAIMER = "Modelled weather from third-party forecast APIs, cached by proximity; "
            + "not an observation at this point and not an official Bureau of Meteorology product.";

    /**
     * Best first: the order the freshness bands are differenced in, and so the order they mean anything in.
     */
    private static final List<String> STATES = List.of("fresh", "stale", "expired");

    /**
     * <strong>Four colours, one per thing this service knows.</strong> The question at two in the morning
     * is not "how fresh is the cache" or "how dry is it" but "what does this service actually know about
     * this ground", and each answer is a different radius on the same point. Drawn as concentric rings
     * they answer it in one look; behind a view selector they are four looks and a memory test.
     */
    private static final Map<String, String> GROUP_COLOUR = Map.of(
            "fresh", "#22c55e", "stale", "#22c55e", "expired", "#22c55e",
            "fire", "#ef4444",
            "drought", "#a78bfa",
            "flood", "#38bdf8");

    /**
     * <strong>Freshness is a line pattern, never a colour</strong> (James, 9 September 2026). Colour on
     * this map means <em>what kind of thing this is</em>, and it can only mean one thing at a time: a
     * grey ring for stale weather is a fifth colour that has to be learnt, and it competes with the four
     * that carry the actual subject. Age is a property of the weather ring, so it is drawn as a property
     * of that line - solid while the cache will serve it, dashed once it is only a last resort, dotted
     * once it is neither.
     */
    private static final Map<String, String> GROUP_DASH = Map.of(
            "stale", "10 6",
            "expired", "2 6");

    /**
     * What each band is called where a person reads it, as against the key it is addressed by.
     */
    private static final Map<String, String> GROUP_LABEL = Map.of(
            "fresh", "weather",
            "stale", "weather · stale",
            "expired", "weather · expired",
            "fire", "fire danger now",
            "drought", "drought",
            "flood", "flood");

    /**
     * How finely a boundary is kept, as a fraction of the radius that drew it. A fortieth is far below
     * what the union's own arcs assert and takes the typical outline down by an order of magnitude.
     */
    private static final double SIMPLIFY_DIVISOR = 40;

    private final WeatherService weather;

    /**
     * Whether this anchor carries a fire index right now, which is what the fire ring is the footprint of.
     * <p>
     * Reads the block the anchor already published rather than recomputing, so the ring, the pin's popup
     * and the API can never disagree. False where no drought cell covers the anchor: an index resting on
     * an assumed drought factor would be a confident number about nothing, and the hole it leaves in the
     * ring is the honest way to say so.
     */
    private static boolean hasFireIndex(Map<String, Object> anchorProperties) {
        return anchorProperties.get("fire") instanceof Map<?, ?> f && f.get("ffdiRating") != null;
    }

    /**
     * One band, or nothing at all when it covers nothing — an empty feature would draw an empty
     * outline and put a zero in the legend for a band that simply is not there.
     *
     * @param exclusive whether this band has had the better bands taken out of it, which is the
     *                  difference between "expired anchors reach here" and "only expired ones do"
     */
    private static void outline(List<Map<String, Object>> out, String group, Geometry g, int members,
                                double radiusMetres, boolean exclusive) {
        Map<String, Object> geometry = Coverage.geoJson(g, radiusMetres / SIMPLIFY_DIVISOR);
        if (geometry == null) {
            return;
        }
        double areaKm2 = round1(Geo.sphericalAreaM2(g) / 1_000_000);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "coverage");
        p.put("group", group);
        p.put("label", GROUP_LABEL.get(group) + " · " + members + (members == 1 ? " point" : " points"));
        // The colour and the pattern travel with the band rather than being mirrored into the renderer,
        // so the outline, the legend beside the switch and any consumer of the public endpoint cannot
        // drift apart. Null dash is a solid line, which is most of them.
        p.put("colour", GROUP_COLOUR.get(group));
        p.put("dash", GROUP_DASH.get(group));
        p.put("members", members);
        p.put("radiusMetres", radiusMetres);
        p.put("areaKm2", areaKm2);
        p.put("pieces", Coverage.pieces(g));
        p.put("exclusive", exclusive);
        out.add(GeoJson.feature("coverage:" + group, geometry, p));
    }

    /**
     * The series from now forward, at most {@code hours} steps of it.
     * <p>
     * Forward of now rather than from the start of the series: an anchor is up to three hours old by
     * the time it is swept, so its first timesteps are history and would push the hours anyone is
     * actually looking at off the end of the cap.
     */
    private static List<Conditions> ahead(List<Conditions> series, Instant now, int hours) {
        List<Conditions> out = new ArrayList<>();
        for (Conditions c : series) {
            if (c.at() != null && c.at().isBefore(now.minus(Duration.ofHours(1)))) {
                continue;
            }
            out.add(c);
            if (hours > 0 && out.size() >= hours) {
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------- the reading

    /**
     * What the cache will actually do with this anchor, on its own clock: answer without a call while
     * it is inside the time-to-live, answer with the age disclosed while it is inside max-stale, and
     * be swept after that. Deliberately not the provider's {@code expiresAt}, which the cache does not
     * currently consult - the two are reported side by side rather than one standing in for the other.
     */
    private static String state(WeatherCache.Anchor a, WeatherProperties props, Instant now) {
        Duration age = Duration.between(a.fetchedAt(), now);
        return age.compareTo(props.cache().ttl()) <= 0 ? "fresh"
                : age.compareTo(props.cache().maxStale()) <= 0 ? "stale" : "expired";
    }

    private static ZoneId zoneOf(WeatherCache.Anchor a, WeatherProperties props) {
        try {
            return a.report().zoneId() == null ? props.zoneId() : ZoneId.of(a.report().zoneId());
        } catch (RuntimeException e) {
            return props.zoneId();
        }
    }

    private static String describe(Duration d) {
        long minutes = d.toMinutes();
        return minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    /**
     * The reading at a point.
     *
     * <p>In the Hub this class also implemented {@code MapLayer}: a {@code spec()} describing itself to
     * the layer catalogue, and a {@code featureCollection(LayerQuery)} the catalogue called. There is no
     * catalogue here — one service, one map, and {@link WeatherApiController} routes to
     * {@link #coverage} directly — so the two catalogue methods did not come across. The colours and
     * line patterns below are still the layer's, because they travel on each band in the GeoJSON rather
     * than living in a renderer.
     *
     * @param force skip the cache and call an upstream. The operator's button, and nothing else: it
     *              spends allowance on every press
     */
    public Map<String, Object> at(double lat, double lon, boolean forecast, boolean force) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());
        body.put("query", Map.of("lat", lat, "lon", lon));
        weather.at(lat, lon, force).ifPresentOrElse(a -> {
            body.put("provenance", WeatherJson.provenance(a));
            body.put("current", WeatherJson.conditions(a.report().current()));
            body.put("fire", WeatherJson.fire(a.fire()));
            body.put("flood", WeatherJson.flood(a.flood()));
            body.put("drought", WeatherJson.drought(a.drought()));
            // One structure, not four parallel arrays: a day arrives with its own weather, fire and flood
            // on it, so nothing downstream has to join three lists on a date string and hope.
            if (forecast) {
                body.put("forecast", WeatherJson.forecast(a.report().daily(), a.fire(), a.flood(),
                        a.report().hourly(), false, weather.zoneOf(a.report())));
            }
        }, () -> {
            body.put("provenance", null);
            body.put("current", null);
            body.put("fire", null);
            body.put("flood", null);
            body.put("drought", null);
            body.put("unavailable", "no provider answered and no cached reading is near enough or recent enough");
        });
        body.put("disclaimer", DISCLAIMER);
        return body;
    }

    /**
     * Every cached thing this service holds, as GeoJSON, with the radius each one is being stretched
     * across and the clock it is being stretched along.
     * <p>
     * Three kinds of feature on three different radii, which is the point of drawing them together: an
     * anchor covers {@code cache.radius-km}, a drought cell covers {@code drought.cell-radius-km}, and
     * a river cell covers far less than either because discharge belongs to a particular watercourse
     * and a generous reuse radius would confidently report the wrong one. Overlapping anchors mean the
     * radius is too small; one anchor spanning terrain that plainly has two different days' weather in
     * it means the radius is too large. Neither is visible in a table of coordinates.
     * <p>
     * Reads nothing it has not already paid for. The drought factor behind each anchor's index comes
     * from {@link DroughtService#cached}, which never spins a cell up, because a layer on a 60-second
     * refresh must not be able to spend allowance.
     *
     * @param hourly include the trimmed hourly series on each anchor. Off by default: seventy-two
     *               timesteps of thirty fields each, times every anchor, is a payload nobody asked for
     * @param hours  how many timesteps forward of now to carry when {@code hourly} is set; zero or
     *               less for the whole series. The cap is what makes this safe to poll - five anchors
     *               carrying a full series is 92 kB, and the anchor ceiling is five hundred
     */
    public Map<String, Object> coverage(boolean hourly, int hours) {
        Instant now = Instant.now();
        WeatherProperties props = weather.properties();
        // One tuning snapshot for the whole response, for the same reason the collections are snapshotted:
        // an hourly step landing mid-build would draw circles of one reach and label them another.
        WeatherTuning t = weather.governor().tuning();
        List<Map<String, Object>> points = new ArrayList<>();

        // Snapshot each collection once. Counted from what was actually drawn, not re-read afterwards:
        // a sweep landing between the two would leave the legend disagreeing with the map.
        List<WeatherCache.Anchor> anchors = weather.cache().all();
        List<DroughtService.Cell> droughtCells = weather.drought().cells();
        List<FloodService.Cell> riverCells = weather.flood().cells();

        // The discs are collected in the same pass that draws the pins, so the outline can never be of
        // a different set of anchors than the one the counts describe.
        Map<String, List<Coverage.Disc>> byState = new LinkedHashMap<>();
        for (String s : STATES) {
            byState.put(s, new ArrayList<>());
        }
        // The fire ring has no radius of its own and never will: the index is a function of an anchor's
        // conditions and a drought cell's factor, so its honest footprint is the anchor's reach, and it
        // simply is not there where no drought cell reaches. That gap is the point of drawing it.
        List<Coverage.Disc> fireDiscs = new ArrayList<>();
        for (WeatherCache.Anchor a : anchors) {
            String state = state(a, props, now);
            byState.get(state).add(new Coverage.Disc(a.lat(), a.lon(), t.anchorReachMetres()));
            Map<String, Object> p = anchor(a, state, props, t, now, hourly, hours);
            if (hasFireIndex(p)) {
                fireDiscs.add(new Coverage.Disc(a.lat(), a.lon(), t.anchorReachMetres()));
            }
            points.add(GeoJson.feature(a.id().toString(), a.lat(), a.lon(), p));
        }
        List<Coverage.Disc> droughtDiscs = new ArrayList<>();
        for (DroughtService.Cell c : droughtCells) {
            droughtDiscs.add(new Coverage.Disc(c.lat(), c.lon(), t.droughtCellRadiusMetres()));
            points.add(GeoJson.feature(c.id().toString(), c.lat(), c.lon(), droughtCell(c, t)));
        }
        List<Coverage.Disc> riverDiscs = new ArrayList<>();
        for (FloodService.Cell c : riverCells) {
            riverDiscs.add(new Coverage.Disc(c.lat(), c.lon(), t.riverCellRadiusMetres()));
            points.add(GeoJson.feature(c.id().toString(), c.lat(), c.lon(), riverCell(c, t)));
        }

        // Outlines first, so a renderer that simply draws the list in order puts the pins on top of
        // the ground they describe rather than underneath it.
        List<Map<String, Object>> features = new ArrayList<>(
                outlines(byState, fireDiscs, droughtDiscs, riverDiscs, t));
        features.addAll(points);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generatedAt", now.toString());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("anchors", anchors.size());
        counts.put("fresh", byState.get("fresh").size());
        counts.put("stale", byState.get("stale").size());
        counts.put("expired", byState.get("expired").size());
        counts.put("droughtCells", droughtCells.size());
        counts.put("riverCells", riverCells.size());
        counts.put("withFireIndex", fireDiscs.size());
        meta.put("counts", counts);
        meta.put("cache", cacheSummary(props, t));
        meta.put("providers", providers());
        meta.put("disclaimer", DISCLAIMER);
        return GeoJson.featureCollection(features, meta);
    }

    /**
     * The same footprint as boundaries: one outline per band, instead of one circle per anchor.
     * <p>
     * A few hundred anchors at a 25 km radius is a few hundred overlapping circles, and past about the
     * fiftieth the interior arcs are the only thing anyone can see. What an operator is actually asking
     * is <em>which ground is covered, and how well</em>, and that is the union — with its holes, which
     * are the gaps and the reason to look at all.
     * <p>
     * The three weather bands are one colour in three line patterns, and are <strong>differenced rather
     * than stacked</strong>, best first: stale is what a stale anchor reaches that no fresh one does,
     * expired what only an expired one reaches. They therefore tile instead of overlapping, and the band
     * a point falls in is the best reading available there, which is the question.
     * <p>
     * Fire, drought and flood are <em>not</em> differenced against the weather bands or each other. They
     * measure different things on different radii, so a drought cell overlapping a fresh anchor is not a
     * worse version of it and must not be subtracted from it — it is the concentric ring inside it, and
     * seeing all four rings at once is the whole point of drawing them together.
     */
    private List<Map<String, Object>> outlines(Map<String, List<Coverage.Disc>> byState,
                                               List<Coverage.Disc> fire,
                                               List<Coverage.Disc> drought, List<Coverage.Disc> river,
                                               WeatherTuning t) {
        Geometry fresh = Coverage.union(byState.get("fresh"));
        Geometry stale = Coverage.without(Coverage.union(byState.get("stale")), fresh);
        Geometry expired = Coverage.without(Coverage.union(byState.get("expired")), fresh.union(stale));

        double anchorRadius = t.anchorReachMetres();
        // Fire before the weather bands, because it shares their radius exactly and the renderer draws it
        // as the wider line underneath them. Where every anchor has an index the rings are the same
        // circle, and one drawn over another simply hides it.
        List<Band> bands = new ArrayList<>(List.of(
                new Band("fire", Coverage.union(fire), fire.size(), anchorRadius, false),
                new Band("expired", expired, byState.get("expired").size(), anchorRadius, true),
                new Band("stale", stale, byState.get("stale").size(), anchorRadius, true),
                new Band("fresh", fresh, byState.get("fresh").size(), anchorRadius, true),
                new Band("drought", Coverage.union(drought), drought.size(), t.droughtCellRadiusMetres(), false),
                new Band("flood", Coverage.union(river), river.size(), t.riverCellRadiusMetres(), false)));
        // Widest first, so a renderer drawing the list in order lays each tighter ring over the broader
        // ones rather than under them. Sorted rather than written in order, because the order is not
        // fixed: a drought cell currently reaches two and a half times as far as an anchor does, and the
        // governor moves both. The sort is stable, so the three that share the anchor's radius keep the
        // order above.
        bands.sort(Comparator.comparingDouble(Band::radiusMetres).reversed());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Band b : bands) {
            outline(out, b.group(), b.geometry(), b.members(), b.radiusMetres(), b.exclusive());
        }
        return out;
    }

    /**
     * One cached reading: what it says, how far it is being stretched, and how long it has left.
     * <p>
     * {@code state} is computed from what the cache actually does, not from what the upstream asked
     * for. Those are two different clocks and this reports both, because an anchor drawn as expired
     * while it is still answering requests would be a display that lies in the safe-looking direction.
     */
    private Map<String, Object> anchor(WeatherCache.Anchor a, String state, WeatherProperties props,
                                       WeatherTuning t, Instant now, boolean hourly, int hours) {
        Duration age = Duration.between(a.fetchedAt(), now);
        Duration ttl = props.cache().ttl();
        Duration maxStale = props.cache().maxStale();
        Instant servesUntil = a.fetchedAt().plus(ttl);
        Instant lastResortUntil = a.fetchedAt().plus(maxStale);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "anchor");
        p.put("label", a.report().provider() + " · " + describe(age));
        p.put("radiusMetres", t.anchorReachMetres());
        p.put("provider", a.report().provider());
        p.put("model", a.report().model());
        // Both heights, deliberately. The model's is what the provider forecast against; the ground's is
        // what the cache matches on. Where they disagree by hundreds of metres that gap is itself the
        // explanation for a reading that looks wrong, and neither number alone would show it.
        p.put("elevationM", a.report().elevationM());
        p.put("terrainM", a.terrainM());
        p.put("zone", a.report().zoneId());

        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("state", state);
        cache.put("fetchedAt", a.fetchedAt().toString());
        cache.put("ageSeconds", age.toSeconds());
        cache.put("age", describe(age));
        // What the cache will actually do with it, on its own clock.
        cache.put("servesUntil", servesUntil.toString());
        cache.put("servesForSeconds", Duration.between(now, servesUntil).toSeconds());
        cache.put("lastResortUntil", lastResortUntil.toString());
        cache.put("lastResortForSeconds", Duration.between(now, lastResortUntil).toSeconds());
        // What the provider asked for, which the cache does not currently enforce; both are shown
        // rather than reconciled, because silently preferring one would hide the disagreement.
        cache.put("providerExpiresAt", a.expiresAt() == null ? null : a.expiresAt().toString());
        cache.put("providerExpired", a.expiresAt() != null && now.isAfter(a.expiresAt()));
        cache.put("ttl", ttl.toString());
        cache.put("maxStale", maxStale.toString());
        cache.put("hits", a.hits().get());
        p.put("cache", cache);

        Conditions current = a.report().current();
        p.put("current", WeatherJson.conditions(current));
        // Both blocks from cached inputs only, so a layer on a sixty-second refresh can never spend
        // allowance. Null where no cell reaches, which is the gap the rings are drawn to show.
        FireWeather fire = current == null || !props.fire().enabled() ? null
                : weather.cachedFireAt(a.lat(), a.lon(), a.report());
        FloodWeather flood = current == null ? null : weather.cachedFloodAt(a.lat(), a.lon(), a.report());
        p.put("fire", WeatherJson.fire(fire));
        p.put("flood", WeatherJson.flood(flood));
        p.put("hourlyCount", a.report().hourly().size());
        p.put("dailyCount", a.report().daily().size());
        // The days are always carried and the hours only on request. Seven days of a dozen fields is a
        // popup's worth; seventy-two timesteps times five hundred anchors is not.
        List<Conditions> ahead = hourly ? ahead(a.report().hourly(), now, hours) : List.of();
        p.put("forecast", WeatherJson.forecast(a.report().daily(), fire, flood, ahead, true, weather.zoneOf(a.report())));
        p.put("hourlyShown", ahead.size());
        p.put("attribution", a.report().attribution());
        return p;
    }

    private Map<String, Object> droughtCell(DroughtService.Cell c, WeatherTuning t) {
        DroughtIndex i = c.index();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "drought");
        p.put("label", "KBDI " + Math.round(i.kbdiMm()) + " mm · DF " + round1(i.droughtFactor()));
        p.put("radiusMetres", t.droughtCellRadiusMetres());
        p.put("drought", WeatherJson.drought(i));
        return p;
    }

    private Map<String, Object> riverCell(FloodService.Cell c, WeatherTuning t) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "river");
        p.put("label", c.hasRiver() ? "river modelled" : "no river within 5 km");
        p.put("radiusMetres", t.riverCellRadiusMetres());
        p.put("hasRiver", c.hasRiver());
        p.put("computedFor", c.computedFor() == null ? null : c.computedFor().toString());
        p.put("days", c.series().size());
        return p;
    }

    /**
     * The knobs themselves, so whoever is looking at the footprint can see what produced it.
     */
    private Map<String, Object> cacheSummary(WeatherProperties props, WeatherTuning t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reachKm", round1(t.anchorReachKm()));
        // The drawn circles are the horizontal footprint. Where this is non-zero the real serving region is
        // pinched wherever terrain rises, so the circle over-claims in hilly country and the map says so.
        m.put("verticalWeight", t.verticalWeight());
        m.put("ttl", t.ttl().toString());
        m.put("maxStale", props.cache().maxStale().toString());
        m.put("maxAnchors", props.cache().maxAnchors());
        m.put("droughtCellRadiusKm", round1(t.droughtCellRadiusKm()));
        m.put("riverCellRadiusKm", round1(t.riverCellRadiusKm()));
        m.put("hits", weather.cache().hitCount());
        m.put("misses", weather.cache().missCount());
        m.put("stale", weather.cache().staleCount());
        m.put("hitRate", percent1(weather.cache().hitRate()));
        m.put("staleRate", percent1(weather.cache().staleRate()));
        return m;
    }

    private List<Map<String, Object>> providers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WeatherStatus s : weather.status()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("usable", s.usable());
            m.put("reason", s.configured() ? s.budgetReason() : s.unavailableReason());
            m.put("spent", s.spent());
            out.add(m);
        }
        return out;
    }

    /**
     * One band before it is drawn, so the five can be put in radius order without reading them back.
     */
    private record Band(String group, Geometry geometry, int members, double radiusMetres, boolean exclusive) {
    }

}
