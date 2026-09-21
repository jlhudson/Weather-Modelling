package au.gully.platform.diagnostics;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * {@code /api/diagnostics}: the service's own state for an agent that reads it and acts. The one
 * write is the clear. {@code window} is an ISO-8601 duration ({@code PT6H}, {@code P2D}), 24 hours
 * by default, 30 days at most. Needs a key with the {@code DIAGNOSTICS} or {@code ALL} scope.
 */
@RestController
@RequestMapping(path = "/api/diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DiagnosticsController {

    private final DiagnosticsLayer layer;
    private final DiagnosticsProperties properties;

    private static String consumer(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }

    private Duration window(String window) {
        if (window == null || window.isBlank()) {
            return properties.window();
        }
        Duration d;
        try {
            d = Duration.parse(window.trim());
        } catch (RuntimeException e) {
            throw bad("window must be an ISO-8601 duration, e.g. PT6H");
        }
        if (d.isNegative() || d.isZero()) {
            throw bad("window must be a positive ISO-8601 duration");
        }
        return d.compareTo(Duration.ofDays(30)) > 0 ? Duration.ofDays(30) : d;
    }

    private static ErrorResponseException bad(String detail) {
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }

    @GetMapping
    public Map<String, Object> summary(@RequestParam(required = false) String window) {
        return layer.summary(window(window));
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(required = false) String level,
                                    @RequestParam(required = false) String window,
                                    @RequestParam(defaultValue = "200") int limit) {
        return layer.logs(level == null ? null : level.trim().toUpperCase(), window(window), limit);
    }

    @GetMapping("/logs/{id}")
    public ResponseEntity<Map<String, Object>> logEvent(@PathVariable long id) {
        return layer.logEvent(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/logs")
    public Map<String, Object> clear(@RequestParam(required = false) String level,
                                     @RequestParam(required = false) String before,
                                     Principal principal) {
        Instant bound;
        try {
            bound = before == null || before.isBlank() ? null : Instant.parse(before.trim());
        } catch (RuntimeException e) {
            throw bad("before must be an ISO-8601 instant");
        }
        String by = consumer(principal);
        int cleared = layer.clear(level == null ? null : level.trim().toUpperCase(), bound, by);
        return Map.of("cleared", cleared, "by", by, "at", Instant.now());
    }

    @DeleteMapping("/logs/{id}")
    public ResponseEntity<Map<String, Object>> clear(@PathVariable long id, Principal principal) {
        String by = consumer(principal);
        return layer.clear(id, by) ? ResponseEntity.ok(Map.of("cleared", 1, "by", by, "at", Instant.now()))
                : ResponseEntity.notFound().build();
    }
}
