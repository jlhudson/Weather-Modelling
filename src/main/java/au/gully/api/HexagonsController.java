package au.gully.api;

import au.gully.hexagons.Cell;
import au.gully.hexagons.Hexagon;
import au.gully.hexagons.HexagonStore;
import au.gully.hexagons.History;
import au.gully.hexagons.MapLayer;
import au.gully.platform.Json;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The hexagons we hold: as a map layer, as a list, and one at a time with everything held for it
 * (docs/06 items 3 and 8). None of these fetch anything.
 */
@RestController
@RequestMapping(path = "/api/v1")
@RequiredArgsConstructor
@Tag(name = "hexagons", description = "The hexagons held, and the map layer")
public class HexagonsController {

    private final HexagonStore store;
    private final MapLayer layer;
    private final History history;
    private final Readings readings;
    private final Json json;

    /**
     * The pre-built layer, with a strong ETag: a client sending it back gets {@code 304} until a
     * hexagon has changed.
     */
    @GetMapping(value = "/hexagons.geojson", produces = "application/geo+json")
    @Operation(summary = "The hexagons as a GeoJSON layer",
            description = "One polygon per hexagon carrying the values a map colours by. Pre-rendered and fingerprinted; "
                    + "send the ETag back and get 304 until something changes. With `at`, the values as they were.")
    public ResponseEntity<byte[]> layer(@RequestParam(required = false) String at,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        MapLayer.Rendered r;
        if (at != null && !at.isBlank()) {
            try {
                r = layer.at(Instant.parse(at.trim()));
            } catch (DateTimeParseException e) {
                throw ReadingsController.bad("at must be an ISO-8601 instant");
            }
        } else {
            r = layer.current();
        }
        if (ifNoneMatch != null && ifNoneMatch.contains(r.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(r.etag()).build();
        }
        return ResponseEntity.ok().eTag(r.etag())
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(r.bytes());
    }

    @GetMapping(value = "/hexagons", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Every hexagon held, one row each")
    public List<Map<String, Object>> list() {
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hexagon h : store.all().stream().sorted(Comparator.comparing(Hexagon::id)).toList()) {
            out.add(row(h, now));
        }
        return out;
    }

    @GetMapping(value = "/hexagons/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Everything held for one hexagon: the reading, its history, its drought state")
    public Map<String, Object> one(@PathVariable String id) {
        Hexagon h = store.get(id).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "no hexagon " + id + " is held"), null));
        Instant now = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>(row(h, now));
        Cell c = h.cell();
        out.put("reading", readings.of(h, new Reading.Point(c.lat(), c.lon()), true));
        out.put("drought", h.drought());
        out.put("river", h.river() == null ? null : h.river().river(java.time.LocalDate.now(store.zoneOf(h))));
        out.put("history", history.of(id, 50));
        out.put("historyCount", history.countFor(id));
        return out;
    }

    public static Map<String, Object> row(Hexagon h, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id());
        m.put("lat", h.cell().lat());
        m.put("lon", h.cell().lon());
        m.put("kind", h.kind());
        m.put("active", h.active());
        m.put("stationId", h.stationId());
        m.put("nearestStationId", h.nearestStationId());
        m.put("nearestStationKm", h.nearestStationKm());
        m.put("fireBanDistrict", h.fireBanDistrict());
        m.put("bureauDistrict", h.bureauDistrict());
        m.put("zone", h.zone());
        m.put("elevationM", h.elevationM());
        m.put("elevationFrom", h.elevationFrom());
        m.put("slopeDeg", h.slopeDeg());
        m.put("landUse", h.landUse() == null ? null : h.landUse().byKey());
        m.put("leads", h.landUse() == null ? null : h.landUse().leads());
        m.put("upstream", h.forecast() == null ? null : h.forecast().upstream());
        m.put("refreshedAt", h.forecast() == null ? null : h.forecast().fetchedAt());
        Instant expires = h.forecast() == null ? null : (h.hasStation() ? h.forecast().forecastExpiresAt() : h.forecast().currentExpiresAt());
        m.put("expiresAt", expires);
        m.put("ageMinutes", h.forecast() == null ? null : Duration.between(h.forecast().fetchedAt(), now).toMinutes());
        m.put("droughtComputedFor", h.drought() == null ? null : h.drought().computedFor());
        m.put("droughtFactor", h.drought() == null ? null : au.gully.science.Numbers.round1(h.drought().droughtFactor()));
        m.put("ffdi", h.fire() == null ? null : h.fire().ffdi());
        m.put("ffdiRating", h.fire() == null ? null : h.fire().ffdiRating());
        m.put("fbi", h.fire() == null || h.fire().grass() == null ? null : h.fire().grass().fbi());
        m.put("officialRating", h.fire() == null || h.fire().official() == null ? null : h.fire().official().rating());
        m.put("warnings", h.fire() == null ? 0 : h.fire().warnings().size());
        m.put("createdAt", h.createdAt());
        m.put("activatedAt", h.activatedAt());
        m.put("lastAskedAt", h.lastAskedAt());
        m.put("lastSnapshotAt", h.lastSnapshotAt());
        m.put("asks", h.asks());
        return m;
    }
}
