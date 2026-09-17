package au.weather.console;

import au.weather.core.WeatherAnswer;
import au.weather.core.WeatherJson;
import au.weather.service.WeatherCache;
import au.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static au.weather.core.Numbers.percent1;

/**
 * The screen that makes the cost visible.
 * <p>
 * Every other guard in this feature is arithmetic that runs where nobody is watching, so the only way
 * to know whether the anchor cache is earning its complexity is to be able to read the hit rate and
 * the allowance spent, side by side, on one page. A fallback to a billed provider that nobody notices
 * until the invoice is not a fallback, it is a surprise.
 */
@Controller
@RequestMapping("/console/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weather;
    private final WeatherCache cache;

    public static String age(Duration d) {
        if (d == null) {
            return "";
        }
        long minutes = d.toMinutes();
        return minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    @GetMapping
    public String page(@RequestParam(required = false) Double lat,
                       @RequestParam(required = false) Double lon,
                       @RequestParam(defaultValue = "false") boolean force,
                       Model model) {
        model.addAttribute("providers", weather.status());
        model.addAttribute("anchors", cache.all());
        model.addAttribute("hits", cache.hitCount());
        model.addAttribute("misses", cache.missCount());
        model.addAttribute("stale", cache.staleCount());
        model.addAttribute("hitRate", percent1(cache.hitRate()));
        model.addAttribute("staleRate", percent1(cache.staleRate()));
        model.addAttribute("config", weather.properties());
        // The values actually in force, which is what every sentence on this page should quote. The
        // configured record is still here for the things the governor does not move.
        model.addAttribute("tuning", weather.governor().tuning());
        model.addAttribute("droughtCells", weather.drought().cells());
        model.addAttribute("riverCells", weather.flood().cells());
        model.addAttribute("now", Instant.now());
        model.addAttribute("lat", lat);
        model.addAttribute("lon", lon);
        if (lat != null && lon != null) {
            Optional<WeatherAnswer> answer = weather.at(lat, lon, force);
            model.addAttribute("answer", answer.orElse(null));
            model.addAttribute("probeFailed", answer.isEmpty());
        }
        return "weather";
    }

    /**
     * Drops every cached anchor, so the next request pays for a fresh reading. An operator action, not a timer.
     */
    @PostMapping("/sweep")
    public String sweep() {
        weather.sweep();
        return "redirect:/console/weather";
    }

    /**
     * Click the map, spend a call. The operator's probe, and the one place in this feature that is
     * <em>supposed</em> to skip the cache.
     * <p>
     * POST rather than GET despite the console's existing force button being a form GET: this one is
     * fired by a click handler, and a request that costs allowance must not be something a browser
     * will repeat on a refresh or a back button.
     * <p>
     * In the Hub this hung off {@code MapController} at {@code /console/map/weather/probe}, because the
     * map was a page of its own with a catalogue of layers on it. There is one map here and it is on
     * this page, so the route came with it; {@code weather-map.js} names the new path.
     */
    @PostMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> probe(@RequestParam double lat,
                                                     @RequestParam double lon,
                                                     @RequestParam(defaultValue = "true") boolean force) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return ResponseEntity.badRequest().body(Map.of("error", "lat must be -90..90 and lon must be -180..180"));
        }
        Optional<WeatherAnswer> answer = weather.at(lat, lon, force);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lat", lat);
        body.put("lon", lon);
        body.put("forced", force);
        answer.ifPresentOrElse(a -> {
            body.put("provenance", WeatherJson.provenance(a));
            body.put("current", WeatherJson.conditions(a.report().current()));
            body.put("fire", WeatherJson.fire(a.fire()));
            body.put("flood", WeatherJson.flood(a.flood()));
            body.put("drought", WeatherJson.drought(a.drought()));
            // The same forecast structure the layer and the API serve, so the popup that opens on a forced
            // call and the popup that opens on a pin are one renderer rather than two.
            body.put("forecast", WeatherJson.forecast(a.report().daily(), a.fire(), a.flood(),
                    a.report().hourly(), true, weather.zoneOf(a.report())));
        }, () -> body.put("unavailable",
                "no provider answered: every one is unconfigured, out of allowance or failing"));
        return ResponseEntity.ok(body);
    }
}
