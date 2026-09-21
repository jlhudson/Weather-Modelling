package au.gully.api;

import au.gully.bureau.StationsFeed;
import au.gully.reach.Probe;
import au.gully.reach.Reaches;
import au.gully.record.Droughts;
import au.gully.bureau.StationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The stations, for a caller with a key: every station as a point with its latest values, one
 * station with its last readings and its reach, and every reach under the rule in force. The same
 * shapes the console map draws.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StationsController {

    private final StationsFeed feed;
    private final Reaches reaches;
    private final Probe probe;
    private final Droughts droughts;
    private final StationRegistry stations;

    @GetMapping(value = "/stations.geojson", produces = {"application/geo+json", "application/json"})
    public Map<String, Object> stations() {
        return feed.geojson();
    }

    @GetMapping(value = "/stations/{id}", produces = "application/json")
    public Map<String, Object> station(@PathVariable String id) {
        Map<String, Object> d = feed.detail(id).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "no station " + id), null));
        d.putAll(reaches.detail(id));
        stations.station(id).ifPresent(s -> d.putAll(droughts.detail(s)));
        return d;
    }

    @GetMapping(value = "/reach.geojson", produces = {"application/geo+json", "application/json"})
    public Map<String, Object> reach() {
        return reaches.geojson();
    }

    /**
     * The stations that speak for a point: those whose reach contains it, nearest first, and the
     * nearest few that do not, with why. The ingredients of a reading, unblended.
     */
    @GetMapping(value = "/stations/at", produces = "application/json")
    public Map<String, Object> at(@RequestParam double lat, @RequestParam double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "lat and lon must be a place on earth"), null);
        }
        return probe.at(lat, lon);
    }
}
