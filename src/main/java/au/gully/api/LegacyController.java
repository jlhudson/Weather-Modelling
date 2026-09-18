package au.gully.api;

import au.gully.hexagons.Geo;
import au.gully.hexagons.MapLayer;
import au.gully.upstreams.Upstreams;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The old {@code /api/weather}, kept answering in its old shape for one release (docs/06 item 3), so
 * a consumer that has not moved to {@code /api/v1} keeps working. Built from the new reading: the
 * old provenance block is filled from the hexagon and the source, the old "estimated" flag is
 * always false and the old "basis" names where the drought came from. Gone with the next release.
 */
@Hidden
@RestController
@RequestMapping(path = "/api/weather", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LegacyController {

    private final Readings readings;
    private final Upstreams upstreams;
    private final MapLayer layer;

    @GetMapping
    public ResponseEntity<Map<String, Object>> at(@RequestParam double lat, @RequestParam double lon,
                                                  @RequestParam(defaultValue = "false") boolean forecast,
                                                  @RequestParam(defaultValue = "false") boolean force) {
        if (!Geo.plausible(lat, lon)) {
            return ResponseEntity.badRequest().body(Map.of("error", "lat must be -90..90 and lon must be -180..180"));
        }
        Reading r = readings.now(lat, lon, forecast, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());
        body.put("query", Map.of("lat", lat, "lon", lon));
        if (!r.available()) {
            body.put("provenance", null);
            body.put("current", null);
            body.put("fire", null);
            body.put("flood", null);
            body.put("drought", null);
            body.put("unavailable", r.unavailable());
            body.put("disclaimer", Reading.DISCLAIMER);
            return ResponseEntity.ok(body);
        }
        body.put("provenance", provenance(r));
        body.put("current", r.current());
        body.put("fire", fire(r));
        body.put("flood", r.flood());
        body.put("drought", r.drought());
        if (forecast && r.forecast() != null) {
            body.put("forecast", r.forecast());
        }
        body.put("disclaimer", Reading.DISCLAIMER);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> provenance(Reading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        Reading.Source s = r.source();
        m.put("provider", s == null ? "bureau" : s.upstream());
        m.put("model", s == null ? null : s.model());
        m.put("attribution", s == null ? "Australian Government Bureau of Meteorology" : s.attribution());
        m.put("observedAt", r.at() == null ? null : r.at().toString());
        m.put("fetchedAt", s == null ? (r.at() == null ? null : r.at().toString()) : s.fetchedAt().toString());
        m.put("ageMinutes", r.at() == null ? null : Duration.between(r.at(), Instant.now()).toMinutes());
        m.put("cached", true);
        m.put("offsetMetres", 0.0);
        m.put("reachMetres", 0.0);
        m.put("elevationDeltaMetres", null);
        m.put("anchor", r.hexagon() == null ? null : r.hexagon().id());
        m.put("anchorLat", r.hexagon() == null ? null : r.hexagon().lat());
        m.put("anchorLon", r.hexagon() == null ? null : r.hexagon().lon());
        m.put("elevationM", r.hexagon() == null ? null : r.hexagon().elevationM());
        m.put("zone", r.hexagon() == null ? null : r.hexagon().zone());
        m.put("decision", "hexagon " + (r.hexagon() == null ? "" : r.hexagon().id()) + ", now from the " + r.currentFrom());
        return m;
    }

    private static Map<String, Object> fire(Reading r) {
        Reading.FireBlock f = r.fire();
        if (f == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ffdi", f.ffdi());
        m.put("ffdiRating", f.ffdiRating());
        m.put("peakFfdi", f.peakFfdi());
        m.put("droughtFactor", f.droughtFactor());
        m.put("kbdiMm", f.kbdiMm());
        m.put("kbdiBand", f.kbdiBand());
        m.put("meanAnnualRainfallMm", r.drought() == null ? null : r.drought().meanAnnualRainfallMm());
        m.put("vapourPressureDeficitKpa", f.vapourPressureDeficitKpa());
        m.put("soilMoistureSurface", f.soilMoistureSurface());
        m.put("soilMoistureRootZone", f.soilMoistureRootZone());
        m.put("boundaryLayerHeightM", f.boundaryLayerHeightM());
        m.put("windSpeed80mKmh", f.windSpeed80mKmh());
        m.put("windDirection80mDeg", f.windDirection80mDeg());
        m.put("capeJkg", f.capeJkg());
        m.put("liftedIndex", f.liftedIndex());
        m.put("estimated", false);
        m.put("basis", r.drought() == null ? "no drought state" : "integrated from " + r.drought().days() + " days of daily rain and maximum temperature");
        return m;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());
        body.put("enabled", true);
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Upstreams.Status s : upstreams.status()) {
            Map<String, Object> p = new LinkedHashMap<>(StatusController.upstream(s));
            p.put("callWeight", s.unitsPerFetch());
            p.put("commercialSafe", s.bills());
            providers.add(p);
        }
        body.put("providers", providers);
        body.put("cache", Map.of("anchors", 0));
        body.put("tuning", Map.of("reason", "the governor was removed in the overhaul; see /api/v1/status"));
        return body;
    }

    @GetMapping(value = "/coverage.geojson", produces = {"application/geo+json", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> coverage() {
        MapLayer.Rendered r = layer.current();
        return ResponseEntity.ok().eTag(r.etag()).contentType(MediaType.parseMediaType("application/geo+json")).body(r.bytes());
    }
}
