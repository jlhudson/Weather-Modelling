package au.gully.hexagons;

import au.gully.bureau.Observation;
import au.gully.bureau.StationRegistry;
import au.gully.platform.Hashing;
import au.gully.platform.Json;
import au.gully.science.Conditions;
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
 * hexagon carrying the handful of values a map colours by — temperature, humidity, wind, the fire
 * indices, the official rating, the drought, the land use, the age — and what the hexagon is.
 * <p>
 * Rendered once per change and kept as bytes with a fingerprint, so a map that polls every minute
 * gets "unchanged" until something changes. The map never triggers a fetch: it draws what is held.
 */
@Service
@RequiredArgsConstructor
public class MapLayer {

    private final HexagonStore store;
    private final StationRegistry stations;
    private final History history;
    private final Json json;
    private final AtomicReference<Rendered> rendered = new AtomicReference<>();

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
     * The layer as it was at an instant, from the history: only hexagons that had an incident have
     * a value then. Not cached; the time slider asks for it rarely.
     */
    public Rendered at(Instant at) {
        return render(store.version(), at);
    }

    private Rendered render(long version, Instant at) {
        Instant now = Instant.now();
        Map<String, History.Snapshot> snapshots = at == null ? Map.of() : history.allAt(at);
        List<Map<String, Object>> features = new ArrayList<>();
        int active = 0, withStation = 0, withForecast = 0;
        for (Hexagon h : store.all()) {
            if (at != null && !snapshots.containsKey(h.id())) {
                continue;
            }
            if (h.active()) active++;
            if (h.hasStation()) withStation++;
            if (h.hasForecast()) withForecast++;
            features.add(feature(h, at == null ? null : snapshots.get(h.id()), now));
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("at", at == null ? null : at.toString());
        meta.put("hexagons", features.size());
        meta.put("active", active);
        meta.put("withStation", withStation);
        meta.put("withForecast", withForecast);
        meta.put("cellKm", store.grid().cellKm());
        meta.put("sides", store.grid().sides());
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
        f.put("properties", snapshot == null ? properties(h, now) : properties(h, snapshot));
        return f;
    }

    /**
     * The values a map colours by, now.
     */
    private Map<String, Object> properties(Hexagon h, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", h.id());
        p.put("kind", h.kind());
        p.put("active", h.active());
        p.put("stationId", h.stationId());
        p.put("nearestStationId", h.nearestStationId());
        p.put("fireBanDistrict", h.fireBanDistrict());
        p.put("bureauDistrict", h.bureauDistrict());
        p.put("elevationM", h.elevationM());
        p.put("slopeDeg", h.slopeDeg());
        p.put("landUse", h.landUse() == null ? null : h.landUse().byKey());
        p.put("leads", h.landUse() == null ? null : h.landUse().leads());
        Forecast fc = h.forecast();
        FirePicture fire = h.fire();
        Conditions c = null;
        String from = null;
        Instant at = null;
        if (h.stationId() != null) {
            Optional<Observation> o = stations.latest(h.stationId());
            if (o.isPresent() && o.get().at() != null && Duration.between(o.get().at(), now).compareTo(FirePictures.STATION_STALE) < 0) {
                c = FirePictures.conditions(o.get());
                from = "station";
                at = o.get().at();
            }
        }
        if (c == null && fc != null && fc.current() != null) {
            c = fc.current();
            from = "model";
            at = fc.current().at();
        }
        p.put("at", at == null ? null : at.toString());
        p.put("from", from);
        p.put("ageMinutes", at == null ? null : Duration.between(at, now).toMinutes());
        p.put("temperatureC", c == null ? null : c.temperatureC());
        p.put("humidityPct", c == null ? null : c.humidityPct());
        p.put("windSpeedKmh", c == null ? null : c.windSpeedKmh());
        p.put("windDirectionDeg", c == null ? null : c.windDirectionDeg());
        p.put("windGustKmh", c == null ? null : c.windGustKmh());
        p.put("precipitationMm", c == null ? null : c.precipitationMm());
        fire(p, fire);
        p.put("upstream", fc == null ? null : fc.upstream());
        p.put("refreshedAt", fc == null ? null : fc.fetchedAt().toString());
        Instant expires = fc == null ? null : (h.hasStation() ? fc.forecastExpiresAt() : fc.currentExpiresAt());
        p.put("expiresAt", expires == null ? null : expires.toString());
        p.put("stale", fc != null && !h.hasStation() && fc.currentExpired(now));
        p.put("warm", h.lastAskedAt() != null && Duration.between(h.lastAskedAt(), now).compareTo(Duration.ofMinutes(15)) < 0);
        p.put("lastAskedAt", h.lastAskedAt() == null ? null : h.lastAskedAt().toString());
        p.put("asks", h.asks());
        return p;
    }

    /**
     * The values as they were, from a snapshot.
     */
    private Map<String, Object> properties(Hexagon h, History.Snapshot s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", h.id());
        p.put("kind", h.kind());
        p.put("active", true);
        p.put("stationId", h.stationId());
        p.put("fireBanDistrict", h.fireBanDistrict());
        p.put("elevationM", h.elevationM());
        p.put("landUse", h.landUse() == null ? null : h.landUse().byKey());
        p.put("leads", h.landUse() == null ? null : h.landUse().leads());
        Conditions c = s.current();
        p.put("at", s.at() == null ? null : s.at().toString());
        p.put("from", s.currentFrom());
        p.put("incident", s.incident());
        p.put("temperatureC", c == null ? null : c.temperatureC());
        p.put("humidityPct", c == null ? null : c.humidityPct());
        p.put("windSpeedKmh", c == null ? null : c.windSpeedKmh());
        p.put("windDirectionDeg", c == null ? null : c.windDirectionDeg());
        p.put("windGustKmh", c == null ? null : c.windGustKmh());
        p.put("precipitationMm", c == null ? null : c.precipitationMm());
        fire(p, s.fire());
        return p;
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
