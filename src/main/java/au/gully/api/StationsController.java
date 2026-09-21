package au.gully.api;

import au.gully.bureau.StationsFeed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The stations, for a caller with a key: every station as a point with its latest values, and one
 * station with its last readings. The same shape the console map draws.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StationsController {

    private final StationsFeed feed;

    @GetMapping(value = "/stations.geojson", produces = {"application/geo+json", "application/json"})
    public Map<String, Object> stations() {
        return feed.geojson();
    }

    @GetMapping(value = "/stations/{id}", produces = "application/json")
    public Map<String, Object> station(@PathVariable String id) {
        return feed.detail(id).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "no station " + id), null));
    }
}
