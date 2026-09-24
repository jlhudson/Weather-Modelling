package au.gully.api;

import au.gully.upstreams.Ledger;
import au.gully.upstreams.Upstreams;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the service has spent upstream (W-28), for a caller with a key: every upstream's allowance and spend, one
 * upstream's units since a moment, and its spend day by day - the ledger the Upstreams page draws, so a consumer's
 * usage page reads the same figures.
 */
@RestController
@RequestMapping("/api/v1/upstreams")
@RequiredArgsConstructor
public class UsageController {

    private final Upstreams upstreams;

    @GetMapping(produces = "application/json")
    public Map<String, Object> all() {
        return Map.of("upstreams", upstreams.status());
    }

    /**
     * Units spent by one upstream since a moment, and the calls; {@code since} defaults to the start of the UTC day.
     */
    @GetMapping(value = "/{id}/spend", produces = "application/json")
    public Map<String, Object> spend(@PathVariable String id, @RequestParam(required = false) String since) {
        Instant from;
        try {
            from = since == null || since.isBlank() ? Instant.now().truncatedTo(ChronoUnit.DAYS) : Instant.parse(since.trim());
        } catch (RuntimeException e) {
            throw bad("since must be an ISO instant");
        }
        Ledger ledger = upstreams.ledger();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upstream", id);
        out.put("since", from.toString());
        out.put("units", ledger.spentSince(id, from));
        return out;
    }

    /**
     * Spend per UTC day, both ends included, every day answered; at most 93 days.
     */
    @GetMapping(value = "/{id}/spend/daily", produces = "application/json")
    public Map<String, Object> daily(@PathVariable String id, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate f, t;
        try {
            t = to == null || to.isBlank() ? today : LocalDate.parse(to.trim());
            f = from == null || from.isBlank() ? t.minusDays(30) : LocalDate.parse(from.trim());
        } catch (RuntimeException e) {
            throw bad("from and to must be dates, e.g. 2026-09-01");
        }
        if (f.isAfter(t) || Duration.between(f.atStartOfDay(), t.atStartOfDay()).toDays() > 92) {
            throw bad("from must be on or before to, and at most 93 days before it");
        }
        List<Ledger.DaySpend> days = upstreams.ledger().daily(id, f, t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upstream", id);
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("days", days);
        return out;
    }

    private static ErrorResponseException bad(String detail) {
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
