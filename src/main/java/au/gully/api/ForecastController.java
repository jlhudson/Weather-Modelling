package au.gully.api;

import au.gully.hexagons.Geo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/forecast?lat=&lon=} (W-20): the whole picture at a point - "now" from the
 * ground, the days and hours ahead from the model, the drought stepped to yesterday, the fire
 * indices now and for every day and hour ahead, the flood picture, the warnings. The reading with
 * {@code forecast=true}, by a name that says what it is.
 */
@RestController
@RequestMapping(path = "/api/v1/forecast", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "forecast", description = "Now, the days and hours ahead, the drought and the fire picture at a point")
public class ForecastController {

    private final Readings readings;

    @GetMapping
    @Operation(summary = "Now, the forecast and the drought at a point",
            description = "The whole reading: `current` from the ground, `forecast` (days and hours, each with its fire "
                    + "indices), `drought`, `fire`, `flood`, `warnings`, `drift`. The same as `/readings?forecast=true`.")
    public ResponseEntity<Reading> forecast(
            @Parameter(description = "latitude, -90..90") @RequestParam double lat,
            @Parameter(description = "longitude, -180..180") @RequestParam double lon,
            @Parameter(description = "what the reading is for - carried on the ask") @RequestParam(required = false) String ref) {
        if (!Geo.plausible(lat, lon)) {
            throw ReadingsController.bad("lat must be -90..90 and lon must be -180..180");
        }
        if (!Geo.inAustralia(lat, lon)) {
            throw ReadingsController.bad("the point is outside Australia, which is all this service holds");
        }
        Reading r = readings.now(lat, lon, true, ref);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        Instant expires = r.hexagon() == null ? null : r.hexagon().expiresAt();
        long seconds = expires == null ? 60 : Math.max(0, Duration.between(Instant.now(), expires).toSeconds());
        b.cacheControl(CacheControl.maxAge(Math.min(seconds, 900), TimeUnit.SECONDS).cachePrivate());
        if (r.at() != null) {
            b.lastModified(r.at());
        }
        return b.body(r);
    }
}
