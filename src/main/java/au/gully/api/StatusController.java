package au.gully.api;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.WarningsReader;
import au.gully.cfs.Curing;
import au.gully.cfs.Districts;
import au.gully.cfs.Ratings;
import au.gully.hexagons.Hexagon;
import au.gully.hexagons.HexagonStore;
import au.gully.hexagons.History;
import au.gully.platform.GullyProperties;
import au.gully.terrain.Terrain;
import au.gully.upstreams.Breaker;
import au.gully.upstreams.Ledger;
import au.gully.upstreams.Upstreams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/status}: everything needed to decide whether it is worth calling this service
 * right now and what it will cost — the upstreams with their allowance and their breaker, the
 * sources and when each last answered, and what is held. Plus the two spend reads per upstream.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "status", description = "Upstreams, sources and what is held")
public class StatusController {

    static final int MAX_SPEND_DAYS = 62;

    private final GullyProperties properties;
    private final Upstreams upstreams;
    private final HexagonStore store;
    private final History history;
    private final StationRegistry stations;
    private final StationReader stationReader;
    private final WarningsReader warnings;
    private final Ratings ratings;
    private final Districts districts;
    private final Curing curing;
    private final Terrain terrain;

    @GetMapping("/status")
    @Operation(summary = "Upstreams, sources and what is held")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.enabled());
        out.put("upstreams", upstreams.status().stream().map(StatusController::upstream).toList());
        out.put("sources", sources());
        out.put("held", held());
        return out;
    }

    static Map<String, Object> upstream(Upstreams.Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("host", s.host());
        m.put("model", s.model());
        m.put("configured", s.configured());
        m.put("unavailableReason", s.unavailableReason());
        m.put("withinBudget", s.withinBudget());
        m.put("budgetReason", s.budgetReason());
        m.put("usable", s.usable());
        m.put("bills", s.bills());
        m.put("unitsPerFetch", s.unitsPerFetch());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("perMinute", s.limits() == null ? null : s.limits().perMinute());
        limits.put("perHour", s.limits() == null ? null : s.limits().perHour());
        limits.put("perDay", s.limits() == null ? null : s.limits().perDay());
        limits.put("perMonth", s.limits() == null ? null : s.limits().perMonth());
        m.put("limits", limits);
        m.put("spent", s.spent());
        Breaker.Status b = s.breaker();
        Map<String, Object> breaker = new LinkedHashMap<>();
        breaker.put("open", b.openUntil() != null);
        breaker.put("openUntil", b.openUntil());
        breaker.put("consecutiveFailures", b.consecutiveFailures());
        breaker.put("lastFailure", b.lastFailure());
        breaker.put("lastFailureAt", b.lastFailureAt());
        breaker.put("openings", b.openings());
        m.put("breaker", breaker);
        m.put("callsInLastMinute", s.callsInLastMinute());
        m.put("attribution", s.attribution());
        return m;
    }

    public Map<String, Object> sources() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> bureau = new LinkedHashMap<>();
        bureau.put("enabled", properties.sources().bureau());
        bureau.put("stations", stations.size());
        bureau.put("lastUpdateAt", stations.lastUpdateAt());
        Map<String, Object> files = new LinkedHashMap<>();
        stationReader.files().forEach((state, f) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("checkedAt", f.checkedAt);
            s.put("readAt", f.readAt);
            s.put("stations", f.stations);
            s.put("failure", f.failure);
            files.put(state, s);
        });
        bureau.put("files", files);
        bureau.put("warnings", warnings.all().size());
        bureau.put("warningsPolledAt", warnings.lastPollAt());
        bureau.put("warningFailures", warnings.failures());
        m.put("bureau", bureau);
        Map<String, Object> cfs = new LinkedHashMap<>();
        cfs.put("enabled", properties.sources().cfs());
        cfs.put("ratingsReadAt", ratings.readAt());
        cfs.put("ratingsFailure", ratings.failure());
        cfs.put("districts", ratings.all().size());
        cfs.put("districtShapesReadAt", districts.readAt());
        cfs.put("districtShapesFailure", districts.failure());
        cfs.put("curingDistricts", curing.all().stream().filter(e -> e.percent() != null).count());
        m.put("cfs", cfs);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("elevationFile", terrain.elevationSource());
        t.put("landCoverFile", terrain.landCoverSource());
        m.put("terrain", t);
        return m;
    }

    public Map<String, Object> held() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Hexagon> all = List.copyOf(store.all());
        m.put("hexagons", all.size());
        m.put("active", all.stream().filter(Hexagon::active).count());
        m.put("withStation", all.stream().filter(Hexagon::hasStation).count());
        m.put("withForecast", all.stream().filter(Hexagon::hasForecast).count());
        m.put("withDrought", all.stream().filter(h -> h.drought() != null).count());
        m.put("droughtAreas", store.droughtAreaCount());
        m.put("snapshots", history.count());
        m.put("served", store.servedCount());
        m.put("fetched", store.fetchedCount());
        m.put("servedStale", store.staleCount());
        m.put("inFlight", store.inFlight());
        m.put("cellKm", store.grid().cellKm());
        m.put("sides", store.grid().sides());
        return m;
    }

    @GetMapping("/upstreams/{id}/spend")
    @Operation(summary = "Units one upstream has spent since an instant")
    public Map<String, Object> spend(@PathVariable String id, @RequestParam String since) {
        Instant from;
        try {
            from = Instant.parse(since);
        } catch (DateTimeParseException e) {
            throw ReadingsController.bad("since must be an ISO-8601 instant, e.g. 2026-09-01T00:00:00Z");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upstream", id);
        body.put("since", from);
        body.put("spent", upstreams.ledger().spentSince(id, from));
        return body;
    }

    @GetMapping("/upstreams/{id}/spend/daily")
    @Operation(summary = "Units one upstream has spent per UTC day, inclusive at both ends, at most 62 days")
    public Map<String, Object> spendDaily(@PathVariable String id, @RequestParam String from, @RequestParam String to) {
        LocalDate start, end;
        try {
            start = LocalDate.parse(from);
            end = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            throw ReadingsController.bad("from and to must be ISO-8601 dates, e.g. 2026-09-01");
        }
        if (end.isBefore(start)) {
            throw ReadingsController.bad("to must not be before from");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_SPEND_DAYS) {
            throw ReadingsController.bad("at most " + MAX_SPEND_DAYS + " days; asked for " + days);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upstream", id);
        body.put("days", upstreams.ledger().daily(id, start, end));
        return body;
    }

    @GetMapping("/upstreams/{id}/spend/hourly")
    @Operation(summary = "Units one upstream has spent per hour over the last day")
    public Map<String, Object> spendHourly(@PathVariable String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upstream", id);
        body.put("hours", upstreams.ledger().hourly(id, Instant.now().minus(Duration.ofHours(24))));
        return body;
    }

    public Ledger ledger() {
        return upstreams.ledger();
    }
}
