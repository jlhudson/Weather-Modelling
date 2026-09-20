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
 * {@code GET /api/v1/now?lat=&lon=} (W-20): what the ground says at a point, and the fire picture
 * drawn from it - the reading's "now" half, in the reading's shape, without the days ahead, the
 * drought or the flood picture. A station hexagon answers it for free; one without a station takes
 * the model's series at this moment, and says so in {@code currentFrom}.
 * <p>
 * The same ask as {@code /readings} without {@code forecast}: a hexagon with a fresh station is
 * not fetched for; one without is, once, for its life.
 */
@RestController
@RequestMapping(path = "/api/v1/now", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "now", description = "What the ground says at a point, and the fire picture from it")
public class NowController {

    private final Readings readings;

    @GetMapping
    @Operation(summary = "Now at a point",
            description = "The reading's \"now\" half: `current` and `currentFrom`, the `station` and the `nearby` blend, "
                    + "`fire`, `warnings` and `drift`; no `forecast`, `drought` or `flood`. The same shape as `/readings`.")
    public ResponseEntity<Reading> now(
            @Parameter(description = "latitude, -90..90") @RequestParam double lat,
            @Parameter(description = "longitude, -180..180") @RequestParam double lon,
            @Parameter(description = "what the reading is for - carried on the ask") @RequestParam(required = false) String ref) {
        if (!Geo.plausible(lat, lon)) {
            throw ReadingsController.bad("lat must be -90..90 and lon must be -180..180");
        }
        if (!Geo.inAustralia(lat, lon)) {
            throw ReadingsController.bad("the point is outside Australia, which is all this service holds");
        }
        Reading r = Readings.nowView(readings.now(lat, lon, false, ref));
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        // "Now" from a station is ten minutes old at most; the model's stands until its life runs out.
        Instant expires = r.hexagon() == null ? null : r.hexagon().expiresAt();
        long seconds = "station".equals(r.currentFrom()) || "stations".equals(r.currentFrom()) || "neighbours".equals(r.currentFrom()) || expires == null
                ? 600 : Math.max(0, Duration.between(Instant.now(), expires).toSeconds());
        b.cacheControl(CacheControl.maxAge(Math.min(seconds, 900), TimeUnit.SECONDS).cachePrivate());
        if (r.at() != null) {
            b.lastModified(r.at());
        }
        return b.body(r);
    }
}
