package au.weather.api;

import au.weather.service.WeatherBudget;
import au.weather.service.WeatherCache;
import au.weather.service.WeatherProperties;
import au.weather.service.WeatherProvider;
import au.weather.service.WeatherService;
import au.weather.service.WeatherStatus;
import au.weather.service.WeatherTuning;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static au.weather.core.Numbers.percent1;
import static au.weather.core.Numbers.round1;

/**
 * {@code GET /api/weather?lat=&lon=}: current conditions and the fire indices at a point,
 * {@code &forecast=true} for the hourly and daily series. API key required, read-only (D-044).
 * <p>
 * Answered from the anchor cache wherever one is near enough and recent enough, which means a consumer
 * polling this endpoint for twenty appliances on one fire ground costs one upstream call, not twenty.
 * Coordinates are validated rather than trusted: a swapped pair puts an Adelaide incident in Kazakhstan
 * and would create an anchor there.
 * <p>
 * {@code GET /api/weather/coverage.geojson} is the cache itself as a map layer: the weather, fire,
 * drought and flood footprints as four concentric outlines over the same ground, plus every anchor and cell
 * with the radius it covers and the clock it is ageing on. Serves only what is already held, so it can
 * be polled by a display without ever spending allowance.
 * <p>
 * {@code /status} and the two {@code /spend} routes are new here. In the Hub the same numbers were
 * only ever read by the console page, which could call the service objects directly; a second
 * application cannot, and "how much of the free allowance is left" is exactly the question a second
 * application needs to be able to ask before it starts calling.
 */
@RestController
@RequestMapping(path = "/api/weather", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class WeatherApiController {

    /**
     * The ceiling on a daily spend query. Two months, because the ledger is pruned well before that and
     * a longer window is a scan per day over rows that are no longer there.
     */
    static final int MAX_SPEND_DAYS = 62;

    private final WeatherLayer layer;
    private final WeatherService weather;
    private final WeatherBudget budget;
    private final WeatherProperties properties;

    /**
     * Everything {@code /status} says about the cache, built from the counters and the live tuning.
     * <p>
     * Static and taking its inputs rather than reading fields, so the shape can be asserted without a
     * Spring context standing behind it.
     */
    public static Map<String, Object> cacheBlock(WeatherCache cache, WeatherProperties props, WeatherTuning t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchors", cache.size());
        m.put("hits", cache.hitCount());
        m.put("misses", cache.missCount());
        m.put("stale", cache.staleCount());
        m.put("hitRate", percent1(cache.hitRate()));
        m.put("staleRate", percent1(cache.staleRate()));
        m.put("reachKm", round1(t.anchorReachKm()));
        m.put("verticalWeight", t.verticalWeight());
        m.put("ttl", t.ttl().toString());
        m.put("maxStale", props.cache().maxStale().toString());
        m.put("maxAnchors", props.cache().maxAnchors());
        return m;
    }

    /**
     * The tuning snapshot as the contract names it. Kilometres rather than metres, because the numbers
     * a person compares against a model grid are kilometres.
     */
    public static Map<String, Object> tuningBlock(WeatherTuning t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchorReachKm", round1(t.anchorReachKm()));
        m.put("droughtCellRadiusKm", round1(t.droughtCellRadiusKm()));
        m.put("riverCellRadiusKm", round1(t.riverCellRadiusKm()));
        m.put("ttl", t.ttl().toString());
        m.put("forecastDays", t.forecastDays());
        m.put("forecastHours", t.forecastHours());
        m.put("verticalWeight", t.verticalWeight());
        m.put("pressure", t.pressure());
        m.put("applied", t.applied());
        m.put("computedAt", t.computedAt() == null ? null : t.computedAt().toString());
        m.put("reason", t.reason());
        return m;
    }

    /**
     * One provider, in full. Everything a caller needs to decide whether it is worth asking and what it
     * will cost, including the licence fact — a free tier that forbids commercial use is not a technical
     * property and belongs in front of whoever decides how this is deployed.
     */
    public static Map<String, Object> providerBlock(WeatherStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("host", s.host());
        m.put("model", s.model());
        m.put("configured", s.configured());
        m.put("unavailableReason", s.unavailableReason());
        m.put("withinBudget", s.withinBudget());
        m.put("budgetReason", s.budgetReason());
        m.put("commercialSafe", s.commercialSafe());
        m.put("callWeight", s.callWeight());
        m.put("guardFraction", s.guardFraction());
        m.put("limits", limits(s.limits()));
        m.put("spent", s.spent());
        m.put("attribution", s.attribution());
        m.put("lastFailure", s.lastFailure());
        m.put("lastFailureAt", s.lastFailureAt() == null ? null : s.lastFailureAt().toString());
        m.put("coolingDownUntil", s.coolingDownUntil() == null ? null : s.coolingDownUntil().toString());
        return m;
    }

    /**
     * The published allowances. Nulls are kept rather than dropped: "this provider publishes no monthly
     * limit" and "this provider's monthly limit is unknown to us" are the same statement here, and an
     * absent key would read as neither.
     */
    public static Map<String, Object> limits(WeatherProvider.Limits l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("perMinute", l == null ? null : l.perMinute());
        m.put("perHour", l == null ? null : l.perHour());
        m.put("perDay", l == null ? null : l.perDay());
        m.put("perMonth", l == null ? null : l.perMonth());
        return m;
    }

    /**
     * The whole of {@code /status}, assembled from parts that were passed in rather than read off a
     * bean. The shape is the contract, so it is tested directly.
     */
    public static Map<String, Object> statusBody(boolean enabled, List<WeatherStatus> providers, Map<String, Object> cache,
                                          Map<String, Object> tuning, int droughtCells, int riverCells) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());
        body.put("enabled", enabled);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeatherStatus s : providers) {
            rows.add(providerBlock(s));
        }
        body.put("providers", rows);
        body.put("cache", cache);
        body.put("tuning", tuning);
        body.put("drought", Map.of("cells", droughtCells));
        body.put("flood", Map.of("cells", riverCells));
        return body;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> at(@RequestParam double lat,
                                                  @RequestParam double lon,
                                                  @RequestParam(defaultValue = "false") boolean forecast,
                                                  @RequestParam(defaultValue = "false") boolean force) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return ResponseEntity.badRequest().body(Map.of("error", "lat must be -90..90 and lon must be -180..180"));
        }
        return ResponseEntity.ok(layer.at(lat, lon, forecast, force));
    }

    /**
     * @param hourly attach the trimmed hourly series to every anchor; off by default, it is a large payload
     * @param hours  timesteps forward of now to carry per anchor, 0 for all of them. Capped at 240,
     *               which is the ceiling on the series itself
     */
    @GetMapping("/coverage.geojson")
    public Map<String, Object> coverage(@RequestParam(defaultValue = "false") boolean hourly,
                                        @RequestParam(defaultValue = "24") int hours) {
        return layer.coverage(hourly, Math.min(Math.max(hours, 0), 240));
    }

    /**
     * Providers, budget, cache and the governor's current tuning: everything needed to answer "is it
     * worth calling this service right now, and what will it cost".
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return statusBody(properties.enabled(), weather.status(),
                cacheBlock(weather.cache(), properties, weather.governor().tuning()),
                tuningBlock(weather.governor().tuning()),
                weather.drought().cells().size(), weather.flood().cells().size());
    }

    /**
     * Allowance units the ledger recorded for one provider since an instant. An unknown provider spends
     * nothing and so answers zero rather than 404: the caller asked what a name has spent, and the
     * honest answer for a name nobody has ever called is none.
     */
    @GetMapping("/spend")
    public ResponseEntity<Map<String, Object>> spend(@RequestParam String provider,
                                                     @RequestParam String since) {
        Instant from;
        try {
            from = Instant.parse(since);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "since must be an ISO-8601 instant, e.g. 2026-09-01T00:00:00Z"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());
        body.put("provider", provider);
        body.put("since", from.toString());
        body.put("spent", budget.spentSince(provider, from));
        return ResponseEntity.ok(body);
    }

    /**
     * The same ledger cut into UTC days, inclusive at both ends.
     * <p>
     * One day's spend is what was spent since its midnight minus what was spent since the next, so a
     * window of N days costs N + 1 queries rather than N scans of the whole ledger. The cap is
     * {@value #MAX_SPEND_DAYS} days and it is a 400 rather than a silent truncation: a chart quietly
     * missing its left-hand half is worse than a chart that did not load.
     */
    @GetMapping("/spend/daily")
    public ResponseEntity<Map<String, Object>> spendDaily(@RequestParam String provider,
                                                          @RequestParam String from,
                                                          @RequestParam String to) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(from);
            end = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "from and to must be ISO-8601 dates, e.g. 2026-09-01"));
        }
        if (end.isBefore(start)) {
            return ResponseEntity.badRequest().body(Map.of("error", "to must not be before from"));
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_SPEND_DAYS) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "at most " + MAX_SPEND_DAYS + " days; asked for " + days));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        double after = budget.spentSince(provider, start.atStartOfDay(ZoneOffset.UTC).toInstant());
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            double afterNext = budget.spentSince(provider, d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            // Never negative: the two queries are a moment apart and a call landing between them would
            // otherwise show as a day that un-spent allowance.
            row.put("spent", Math.max(0, after - afterNext));
            rows.add(row);
            after = afterNext;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());
        body.put("provider", provider);
        body.put("days", rows);
        return ResponseEntity.ok(body);
    }
}
