package au.gully.api;

import au.gully.hexagons.Geo;
import au.gully.hexagons.History;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/readings?lat=&lon=}: the reading at a point, now or at a time (docs/06 items 1,
 * 2 and 3). One point per request, always: The Hub sends its calls one after the other, and a large
 * fire is two to ten calls — the head, the flanks, the ends — each answered from its own hexagon.
 * <p>
 * The headers let a client cache correctly: {@code Cache-Control} runs to the reading's own expiry,
 * {@code Last-Modified} is when the "now" values were taken, and the strong {@code ETag} the filter
 * adds is a hash of the body, which no longer carries a generated-at time and so only changes when
 * the reading does.
 */
@RestController
@RequestMapping(path = "/api/v1/readings", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "readings", description = "The weather, and the fire danger, at a point")
public class ReadingsController {

    private final Readings readings;
    private final History history;

    @GetMapping
    @Operation(summary = "The reading at a point",
            description = "Now by default. With `at`, the snapshot nearest that time for the point's hexagon — "
                    + "only hexagons that had an incident have history. With `incident`, the ask writes history "
                    + "(at most once every three hours per hexagon).")
    public ResponseEntity<Reading> at(
            @Parameter(description = "latitude, -90..90") @RequestParam double lat,
            @Parameter(description = "longitude, -180..180") @RequestParam double lon,
            @Parameter(description = "include the days and hours ahead") @RequestParam(defaultValue = "false") boolean forecast,
            @Parameter(description = "an ISO-8601 instant in the past; answers from history") @RequestParam(required = false) String at,
            @Parameter(description = "the incident present at the point, which writes history") @RequestParam(required = false) String incident) {
        if (!Geo.plausible(lat, lon)) {
            throw bad("lat must be -90..90 and lon must be -180..180");
        }
        if (!Geo.inAustralia(lat, lon)) {
            throw bad("the point is outside Australia, which is all this service holds");
        }
        if (at != null && !at.isBlank()) {
            Instant when;
            try {
                when = Instant.parse(at.trim());
            } catch (DateTimeParseException e) {
                throw bad("at must be an ISO-8601 instant, e.g. 2026-09-18T02:00:00Z");
            }
            if (when.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
                throw bad("at is in the future; leave it out for the reading now");
            }
            Reading r = readings.at(lat, lon, when, history);
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                    .lastModified(r.at() == null ? when : r.at()).body(r);
        }
        Reading r = readings.now(lat, lon, forecast, incident);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        Instant expires = r.hexagon() == null ? null : r.hexagon().expiresAt();
        long seconds = expires == null ? 60 : Math.max(0, Duration.between(Instant.now(), expires).toSeconds());
        b.cacheControl(CacheControl.maxAge(Math.min(seconds, 900), TimeUnit.SECONDS).cachePrivate());
        if (r.at() != null) {
            b.lastModified(r.at());
        }
        return b.body(r);
    }

    static ErrorResponseException bad(String detail) {
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
