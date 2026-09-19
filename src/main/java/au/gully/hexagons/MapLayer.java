package au.gully.hexagons;

import au.gully.platform.Hashing;
import au.gully.platform.Json;
import au.gully.science.Conditions;
import au.gully.science.LandUse;
import au.gully.upstreams.Forecast;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hexagons we hold, as map shapes with their values (docs/06 items 3 and 14): one polygon per
 * hexagon carrying two sets of values kept apart — <em>now</em>, from the ground (the station in
 * the hexagon, the stations in it blended, or the neighbours brought to its elevation) and the
 * <em>forecast</em>, the model's series read at this moment — with the drift between them, the fire
 * indices, the drought, the land use, the elevation, and when the hexagon was last asked about.
 * The map colours by either set, or by the difference, and fades a forecast as its life runs out.
 * <p>
 * Rendered once per change and kept as bytes with a fingerprint, so a map that polls every minute
 * gets "unchanged" until something changes. The map never triggers a fetch: it draws what is held.
 */
@Service
@RequiredArgsConstructor
public class MapLayer {

    private final HexagonStore store;
    private final History history;
    private final FirePictures pictures;
    private final Life life;
    private final Drifts drifts;
    private final Json json;
    private final AtomicReference<Rendered> rendered = new AtomicReference<>();
    /** The day's mean drift per hexagon, read once per render rather than once per hexagon. */
    private Map<String, Double> driftDay = Map.of();

    /**
     * The layer as it stands: rebuilt only when a hexagon has been replaced since the last render.
     */
    public Rendered current() {
        Rendered held = rendered.get();
        long version = store.version();
        if (held != null && held.version() == version) {
            return held;
        }
        synchronized (rendered) {
            held = rendered.get();
            if (held != null && held.version() == version) {
                return held;
            }
            Rendered fresh = render(version, null);
            rendered.set(fresh);
            return fresh;
        }
    }

    /**
     * The layer as it was at an instant, from the history: only hexagons asked about with a ref have
     * a value then. Not cached; the time slider asks for it rarely.
     */
    public Rendered at(Instant at) {
        return render(store.version(), at);
    }

    private Rendered render(long version, Instant at) {
        Instant now = Instant.now();
        Map<String, History.Snapshot> snapshots = at == null ? Map.of() : history.allAt(at);
        driftDay = at == null ? drifts.meanScores(Duration.ofHours(24)) : Map.of();
        List<Map<String, Object>> features = new ArrayList<>();
        int active = 0, withStation = 0, withForecast = 0, withDrought = 0, withLandUse = 0;
        Map<String, Integer> nowFrom = new LinkedHashMap<>();
        for (Hexagon h : store.all()) {
            if (at != null && !snapshots.containsKey(h.id())) {
                continue;
            }
            if (h.active()) active++;
            if (h.hasStation()) withStation++;
            if (h.hasForecast()) withForecast++;
            if (h.drought() != null) withDrought++;
            if (h.landUse() != null) withLandUse++;
            Map<String, Object> f = feature(h, at == null ? null : snapshots.get(h.id()), now);
            @SuppressWarnings("unchecked")
            Object from = ((Map<String, Object>) f.get("properties")).get("from");
            nowFrom.merge(from == null ? "none" : from.toString(), 1, Integer::sum);
            features.add(f);
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("at", at == null ? null : at.toString());
        meta.put("renderedAt", now.toString());
        meta.put("hexagons", features.size());
        meta.put("active", active);
        meta.put("withStation", withStation);
        meta.put("withForecast", withForecast);
        meta.put("withDrought", withDrought);
        meta.put("withLandUse", withLandUse);
        meta.put("nowFrom", nowFrom);
        meta.put("cellKm", store.grid().cellKm());
        meta.put("lifeMinutes", life.forecast().toMinutes());
        meta.put("version", version);
        fc.put("meta", meta);
        byte[] bytes = json.write(fc).getBytes(StandardCharsets.UTF_8);
        return new Rendered(version, bytes, "\"" + Hashing.sha256Hex(bytes).substring(0, 20) + "\"", now);
    }

    private Map<String, Object> feature(Hexagon h, History.Snapshot snapshot, Instant now) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("id", h.id());
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Polygon");
        List<List<Double>> ring = new ArrayList<>();
        for (double[] v : store.grid().outline(h.cell())) {
            ring.add(List.of(round(v[1]), round(v[0])));
        }
        geometry.put("coordinates", List.of(ring));
        f.put("geometry", geometry);
        f.put("properties", snapshot == null ? properties(h, now) : properties(h, snapshot, now));
        return f;
    }

    /**
     * The values a map colours by, now.
     */
    private Map<String, Object> properties(Hexagon h, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        what(p, h);
        // "Now" as the reading would answer it: the station, the stations blended, the neighbours brought
        // to this elevation, or - marked as such - the model.
        Optional<FirePictures.Now> n = h.active() || h.hasStation() ? pictures.now(h, now) : Optional.empty();
        Conditions c = n.map(FirePictures.Now::conditions).orElse(null);
        Interpolation.Result nearby = n.map(FirePictures.Now::nearby).orElse(null);
        now(p, c, n.map(FirePictures.Now::from).orElse(null), n.map(FirePictures.Now::at).orElse(null), now);
        Integer nowStations = nearby != null ? Integer.valueOf(nearby.stations().size()) : "station".equals(p.get("from")) ? Integer.valueOf(1) : null;
        p.put("nowStations", nowStations);
        p.put("nowRing", nearby == null ? null : nearby.ring());
        p.put("nowElevationApplied", nearby != null && nearby.elevationApplied());
        // The forecast, kept apart: the model's series read at this moment, and how much life it has left.
        Forecast fc = h.forecast();
        forecast(p, fc, fc == null ? null : fc.at(now), now);
        fire(p, h.fire());
        // The stations' word on the forecast (W-12), and how the forecasts here have been doing over a day.
        Drift d = drifts.latest(h.id()).orElse(null);
        p.put("drift", d == null ? null : d.score());
        p.put("drifted", d != null && d.drifted());
        p.put("driftWorst", d == null ? null : d.worst());
        p.put("driftTemperatureC", d == null ? null : d.temperatureC());
        p.put("driftHumidityPct", d == null ? null : d.humidityPct());
        p.put("driftWindKmh", d == null ? null : d.windKmh());
        p.put("driftRainMm", d == null ? null : d.rainMm());
        p.put("driftAt", d == null ? null : d.at().toString());
        p.put("drift24h", driftDay.get(h.id()));
        // The asks: what keeps a hexagon warm, and what caused every read the sources saw.
        p.put("warm", h.lastAskedAt() != null && Duration.between(h.lastAskedAt(), now).compareTo(Duration.ofMinutes(15)) < 0);
        p.put("lastAskedAt", h.lastAskedAt() == null ? null : h.lastAskedAt().toString());
        p.put("askedMinutesAgo", h.lastAskedAt() == null ? null : Duration.between(h.lastAskedAt(), now).toMinutes());
        p.put("asks", h.asks());
        return p;
    }

    /**
     * The values as they were, from a snapshot: what was "now" then, from where; no forecast, no drift.
     */
    private Map<String, Object> properties(Hexagon h, History.Snapshot s, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        what(p, h);
        p.put("active", true);
        p.put("hasForecast", true);
        p.put("hasDrought", s.fire() != null && s.fire().droughtFactor() != null);
        now(p, s.current(), s.currentFrom(), s.at(), now);
        p.put("ref", s.ref());
        forecast(p, null, null, now);
        fire(p, s.fire());
        return p;
    }

    /**
     * What the hexagon is: the ground, the station, the district, and what makes it active.
     */
    private static void what(Map<String, Object> p, Hexagon h) {
        p.put("id", h.id());
        p.put("kind", h.kind());
        p.put("active", h.active());
        // What makes the hexagon active, each on its own: a station in it, a forecast held because
        // someone asked, the drought stepped for it. A map draws these before any value.
        p.put("hasStation", h.hasStation());
        p.put("hasForecast", h.hasForecast());
        p.put("hasDrought", h.drought() != null);
        p.put("lat", round(h.cell().lat()));
        p.put("lon", round(h.cell().lon()));
        p.put("stationId", h.stationId());
        p.put("nearestStationId", h.nearestStationId());
        p.put("fireBanDistrict", h.fireBanDistrict());
        p.put("bureauDistrict", h.bureauDistrict());
        p.put("elevationM", h.elevationM());
        p.put("elevationFrom", h.elevationFrom());
        p.put("slopeDeg", h.slopeDeg());
        LandUse land = h.landUse();
        p.put("landUse", land == null ? null : land.byKey());
        p.put("landDominant", land == null || land.dominant() == null ? null : land.dominant().key());
        p.put("leads", land == null ? null : land.leads());
        p.put("burnablePct", land == null ? null : land.burnablePct());
        p.put("landSource", land == null ? null : land.source());
    }

    /**
     * "Now": the ground's values, and where they came from.
     */
    private static void now(Map<String, Object> p, Conditions c, String from, Instant at, Instant now) {
        p.put("from", from);
        p.put("observed", "station".equals(from) || "stations".equals(from));
        p.put("nowAt", at == null ? null : at.toString());
        p.put("nowAgeMinutes", at == null ? null : Duration.between(at, now).toMinutes());
        p.put("nowTemperatureC", c == null ? null : c.temperatureC());
        p.put("nowHumidityPct", c == null ? null : c.humidityPct());
        p.put("nowWindKmh", c == null ? null : c.windSpeedKmh());
        p.put("nowWindDeg", c == null ? null : c.windDirectionDeg());
        p.put("nowGustKmh", c == null ? null : c.windGustKmh());
        p.put("nowRainMm", c == null ? null : c.precipitationMm());
        p.put("nowApparentC", c == null ? null : c.apparentTemperatureC());
        p.put("nowDewPointC", c == null ? null : c.dewPointC());
    }

    /**
     * The forecast: the model's values at this moment, when it was fetched, when its life ends.
     */
    private void forecast(Map<String, Object> p, Forecast fc, Conditions m, Instant now) {
        p.put("upstream", fc == null ? null : fc.upstream());
        p.put("model", fc == null ? null : fc.model());
        p.put("fcFetchedAt", fc == null ? null : fc.fetchedAt().toString());
        p.put("fcAgeMinutes", fc == null ? null : Duration.between(fc.fetchedAt(), now).toMinutes());
        Instant expires = life.expiresAt(fc);
        p.put("fcExpiresAt", expires == null ? null : expires.toString());
        p.put("fcMinutesLeft", expires == null ? null : Math.max(0, Duration.between(now, expires).toMinutes()));
        p.put("stale", fc != null && life.expired(fc, now));
        p.put("fcTemperatureC", m == null ? null : m.temperatureC());
        p.put("fcHumidityPct", m == null ? null : m.humidityPct());
        p.put("fcWindKmh", m == null ? null : m.windSpeedKmh());
        p.put("fcWindDeg", m == null ? null : m.windDirectionDeg());
        p.put("fcGustKmh", m == null ? null : m.windGustKmh());
        p.put("fcRainMm", m == null ? null : m.precipitationMm());
        p.put("fcApparentC", m == null ? null : m.apparentTemperatureC());
        // Now against the forecast, where both are here: what the drift score is made of, per value.
        p.put("diffTemperatureC", diff(p.get("nowTemperatureC"), p.get("fcTemperatureC")));
        p.put("diffHumidityPct", diff(p.get("nowHumidityPct"), p.get("fcHumidityPct")));
        p.put("diffWindKmh", diff(p.get("nowWindKmh"), p.get("fcWindKmh")));
    }

    private static Double diff(Object now, Object forecast) {
        if (!(now instanceof Number a) || !(forecast instanceof Number b)) {
            return null;
        }
        return Math.round((a.doubleValue() - b.doubleValue()) * 10) / 10.0;
    }

    private static void fire(Map<String, Object> p, FirePicture fire) {
        p.put("ffdi", fire == null ? null : fire.ffdi());
        p.put("ffdiRating", fire == null ? null : fire.ffdiRating());
        p.put("peakFfdi", fire == null ? null : fire.peakFfdi());
        p.put("droughtFactor", fire == null ? null : fire.droughtFactor());
        p.put("kbdiMm", fire == null ? null : fire.kbdiMm());
        p.put("kbdiBand", fire == null ? null : fire.kbdiBand());
        FirePicture.Grass g = fire == null ? null : fire.grass();
        p.put("gfdi", g == null ? null : g.gfdi());
        p.put("gfdiRating", g == null ? null : g.gfdiRating());
        p.put("fbi", g == null ? null : g.fbi());
        p.put("afdrsRating", g == null ? null : g.afdrsRating());
        p.put("curingPct", g == null ? null : g.curingPct());
        FirePicture.Official o = fire == null ? null : fire.official();
        p.put("officialRating", o == null ? null : o.rating());
        p.put("officialFbi", o == null ? null : o.fbi());
        p.put("totalFireBan", o != null && o.totalFireBan());
        p.put("fireWeatherWarning", fire != null && fire.fireWeatherWarning());
        p.put("warnings", fire == null ? 0 : fire.warnings().size());
        p.put("windChangeAt", fire == null || fire.wind() == null || fire.wind().change() == null ? null : fire.wind().change().at().toString());
    }

    private static double round(double degrees) {
        return Math.round(degrees * 100_000.0) / 100_000.0;
    }

    /**
     * One rendering: the bytes, the fingerprint a client sends back, and when it was made.
     */
    public record Rendered(long version, byte[] bytes, String etag, Instant renderedAt) {
    }
}
