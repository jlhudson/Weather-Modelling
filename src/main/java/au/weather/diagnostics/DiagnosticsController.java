package au.weather.diagnostics;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * {@code /api/diagnostics}: the system's own state for an agent that reads it and acts (D-234). API
 * key required like every layer (D-122); the one write is the clear, which deletes what has been
 * read and acted on so the next read holds only what has happened since.
 *
 * <ul>
 *   <li>{@code GET /api/diagnostics?window=} — where to look: startup, the top log signatures, and the
 *   weather block — providers, budget, cache and the governor's current tuning.</li>
 *   <li>{@code GET /api/diagnostics/logs?level=&source=&window=&limit=} — the warning and error
 *   signatures, most recent first; {@code GET /api/diagnostics/logs/{id}} adds the trace.</li>
 *   <li>{@code DELETE /api/diagnostics/logs?level=&source=&before=} and {@code DELETE /api/diagnostics/logs/{id}}
 *   — the clear, logged with the consumer's name.</li>
 * </ul>
 *
 * <p>{@code window} is an ISO-8601 duration ({@code PT6H}, {@code P2D}) and defaults to the layer's
 * 24 hours: a short look-back on purpose, because this is read when something is wrong now.
 *
 * <p>The Hub's two source reads, {@code /sources} and {@code /sources/&#123;id&#125;}, are not here:
 * there is no source register in this service. The {@code source} query parameter survives on the log
 * reads because the row shape is shared across the three applications, and it will match nothing.
 */
@RestController
@RequestMapping(path = "/api/diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DiagnosticsController {

    private final DiagnosticsLayer layer;
    private final DiagnosticsProperties properties;

    /**
     * {@code Principal.getName()} is the consumer: the key filter's authentication answers the key's
     * consumer as its principal, so a clear is logged against a name and not a key.
     */
    private static String consumer(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }

    private Duration window(String window) {
        if (window == null || window.isBlank()) {
            return properties.window();
        }
        Duration d = Duration.parse(window.trim());
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("window must be a positive ISO-8601 duration");
        }
        return d.compareTo(Duration.ofDays(30)) > 0 ? Duration.ofDays(30) : d;
    }

    @GetMapping
    public Map<String, Object> summary(@RequestParam(required = false) String window) {
        return layer.summary(window(window));
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(required = false) String level,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) String window,
                                    @RequestParam(defaultValue = "200") int limit) {
        return layer.logs(level == null ? null : level.trim().toUpperCase(), source, window(window), limit);
    }

    @GetMapping("/logs/{id}")
    public ResponseEntity<Map<String, Object>> logEvent(@PathVariable long id) {
        return layer.logEvent(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/logs")
    public Map<String, Object> clear(@RequestParam(required = false) String level,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(required = false) String before,
                                     Principal principal) {
        Instant bound = before == null || before.isBlank() ? null : Instant.parse(before.trim());
        String by = consumer(principal);
        int cleared = layer.clear(level == null ? null : level.trim().toUpperCase(), source, bound, by);
        return Map.of("cleared", cleared, "by", by, "at", Instant.now());
    }

    @DeleteMapping("/logs/{id}")
    public ResponseEntity<Map<String, Object>> clear(@PathVariable long id, Principal principal) {
        String by = consumer(principal);
        return layer.clear(id, by) ? ResponseEntity.ok(Map.of("cleared", 1, "by", by, "at", Instant.now()))
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage()));
    }
}
