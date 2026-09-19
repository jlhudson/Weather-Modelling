package au.gully.api;

import au.gully.hexagons.Drift;
import au.gully.hexagons.Drifts;
import au.gully.hexagons.HexagonStore;
import au.gully.hexagons.Life;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the forecasts are doing against the stations (W-12): the ledger of every comparison, summed
 * per hexagon over a window, and the recent comparisons themselves. This is where "are the
 * forecasts good enough here" is answered with numbers rather than an impression.
 */
@RestController
@RequestMapping(path = "/api/v1/drift", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "drift", description = "The stations against the forecasts")
public class DriftController {

    private final Drifts drifts;
    private final HexagonStore store;
    private final Life life;

    @GetMapping
    @Operation(summary = "Per hexagon over a window: comparisons, mean and worst score, forecasts thrown out, the latest comparison",
            description = "A score is the worst of temperature, humidity, wind speed and rain as a share of its tolerance: 0 agrees, 1 threw the forecast out.")
    public Map<String, Object> summary(@Parameter(description = "the window back from now, hours, 1 to 720") @RequestParam(defaultValue = "24") int hours) {
        int h = Math.max(1, Math.min(hours, 720));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowHours", h);
        out.put("tolerances", tolerances());
        out.put("life", Map.of("forecast", Life.FORECAST.toString(), "forecastWhenTight", Life.FORECAST_WHEN_TIGHT.toString(),
                "tightAt", Life.TIGHT_AT, "inForce", life.forecast().toString(), "tight", life.tight(),
                "refetchAfterDrift", HexagonStore.REFETCH_AFTER_DRIFT.toString()));
        out.put("hexagons", drifts.summary(Duration.ofHours(h)));
        return out;
    }

    @GetMapping("/recent")
    @Operation(summary = "The comparisons, newest first, for one hexagon or all")
    public Map<String, Object> recent(@RequestParam(required = false) String hexagon,
                                      @RequestParam(defaultValue = "200") int limit) {
        List<Drift> rows = drifts.recent(hexagon == null || hexagon.isBlank() ? null : hexagon.trim(), limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hexagon", hexagon);
        out.put("comparisons", rows);
        return out;
    }

    static Map<String, Object> tolerances() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("temperatureC", Drift.TEMPERATURE_C);
        t.put("humidityPct", Drift.HUMIDITY_PCT);
        t.put("windKmh", Drift.WIND_KMH);
        t.put("rainMm", Drift.RAIN_MM);
        return t;
    }
}
