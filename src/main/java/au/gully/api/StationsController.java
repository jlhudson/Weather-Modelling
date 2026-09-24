package au.gully.api;

import au.gully.bureau.StationsFeed;
import au.gully.reach.Probe;
import au.gully.reach.Reaches;
import au.gully.reading.Readings;
import au.gully.reading.StationDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * The stations, for a caller with a key: every station as a point with its latest values, one
 * station with everything held for it, every reach under the rule in force, the stations that speak for
 * a point, and the reading there. The same shapes the console map draws.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StationsController {

    private final StationsFeed feed;
    private final Reaches reaches;
    private final Probe probe;
    private final StationDetails details;
    private final Readings readings;
    private final au.gully.cfs.FireBan fireBan;
    private final au.gully.bureau.Warnings warnings;

    @GetMapping(value = "/stations.geojson", produces = {"application/geo+json", "application/json"})
    public Map<String, Object> stations() {
        return feed.geojson();
    }

    /**
     * One station: what it last said and its last readings, its reach, its drought, and its forecast (W-20).
     */
    @GetMapping(value = "/stations/{id}", produces = "application/json")
    public Map<String, Object> station(@PathVariable String id) {
        return details.of(id).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "no station " + id), null));
    }

    /**
     * The Bureau's warnings in force in South Australia (W-25), each with the areas it covers.
     */
    @GetMapping(value = "/warnings", produces = "application/json")
    public Map<String, Object> warnings() {
        Instant now = Instant.now();
        return Map.of("warnings", warnings.ensure(now).stream().filter(w -> w.until() == null || w.until().isAfter(now)).map(au.gully.bureau.Warnings::view).toList(),
                "readAt", String.valueOf(warnings.readAt()));
    }

    /**
     * South Australia's fire ban districts with what the CFS has published for each today and ahead (W-23).
     */
    @GetMapping(value = "/districts.geojson", produces = {"application/geo+json", "application/json"})
    public Map<String, Object> districts() {
        return fireBan.geojson(Instant.now());
    }

    @GetMapping(value = "/reach.geojson", produces = {"application/geo+json", "application/json"})
    public Map<String, Object> reach() {
        return reaches.geojson();
    }

    /**
     * The reading at a point (W-8): the weather now and the drought, blended from the stations whose
     * reach contains it - or from a point of our own where none can say - the fire danger from
     * the blend, and the forecast of the nearest station in reach (W-20). A place nobody's reach contains
     * is dropped as a point on the first ask, which may take a few seconds; every later ask inside its
     * reach is immediate. With {@code force=true} (W-13) the upstreams are asked first - the Bureau's
     * file now, the days the stations in reach are missing, a point of ours' current and the forecast
     * again - and {@code grabbed} says what came. With {@code ref} (W-27) the reading is kept for that reference, at most
     * once every three hours; with {@code at}, a past moment, it is answered from what was kept or from the stations' record.
     */
    @GetMapping(value = "/reading", produces = "application/json")
    public Map<String, Object> reading(@RequestParam double lat, @RequestParam double lon, @RequestParam(defaultValue = "false") boolean force,
                                       @RequestParam(required = false) String ref, @RequestParam(required = false) String at) {
        onEarth(lat, lon);
        Instant now = Instant.now();
        if (at != null && !at.isBlank()) {
            Instant when;
            try {
                when = Instant.parse(at.trim());
            } catch (RuntimeException e) {
                throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "at must be an ISO instant, e.g. 2026-09-25T03:00:00Z"), null);
            }
            if (when.isAfter(now)) {
                throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "at is in the future; ask without it for now"), null);
            }
            return readings.past(lat, lon, when, ref, now);
        }
        return readings.at(lat, lon, now, force, ref);
    }

    /**
     * The stations that speak for a point: those whose reach contains it, nearest first, and the
     * nearest few that do not, with why. The ingredients of a reading, unblended.
     */
    @GetMapping(value = "/stations/at", produces = "application/json")
    public Map<String, Object> at(@RequestParam double lat, @RequestParam double lon) {
        onEarth(lat, lon);
        return probe.at(lat, lon);
    }

    private static void onEarth(double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180 || Double.isNaN(lat) || Double.isNaN(lon)) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "lat and lon must be a place on earth"), null);
        }
    }
}
