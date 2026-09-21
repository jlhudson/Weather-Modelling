package au.gully.console;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.StationsFeed;
import au.gully.platform.Status;
import au.gully.reach.Probe;
import au.gully.reach.ReachRule;
import au.gully.reach.Reaches;
import au.gully.reach.TerrainSampler;
import au.gully.reading.Readings;
import au.gully.record.Droughts;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The map: the Bureau's stations as points, each with what it last said; every station's reach
 * under the rule in force, or under the rule the sliders ask about; and a click that opens
 * everything held for one. The feeds are the API's own shapes; the map draws what a caller gets.
 */
@Controller
@RequestMapping("/console/map")
@RequiredArgsConstructor
public class MapController {

    private final StationsFeed feed;
    private final StationReader reader;
    private final StationRegistry stations;
    private final Status status;
    private final Reaches reaches;
    private final ReachRule rule;
    private final TerrainSampler sampler;
    private final Probe probe;
    private final Droughts droughts;
    private final Readings readings;

    @GetMapping
    public String page(Model model) {
        ReachRule.Rule r = rule.current();
        model.addAttribute("reachKm", r.reachKm());
        model.addAttribute("reachMaxKm", ReachRule.MAX_KM);
        model.addAttribute("reachMinKm", ReachRule.MIN_KM);
        model.addAttribute("kmPer100m", r.kmPer100m());
        model.addAttribute("kmPer100mMax", ReachRule.MAX_KM_PER_100M);
        model.addAttribute("coastalKm", r.coastalKm());
        model.addAttribute("reachBy", rule.by());
        model.addAttribute("reachSince", rule.since() == null ? null : rule.since().toString());
        return "map";
    }

    @GetMapping(value = "/stations.geojson", produces = "application/geo+json")
    @ResponseBody
    public Map<String, Object> stations() {
        return feed.geojson();
    }

    @GetMapping(value = "/station/{id}", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> station(@PathVariable String id) {
        return feed.detail(id).map(d -> {
            d.putAll(reaches.detail(id));
            stations.station(id).ifPresent(s -> d.putAll(droughts.detail(s)));
            return ResponseEntity.ok(d);
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Every station's reach: under the rule in force, or under the rule asked about (the sliders'
     * preview), which sets nothing.
     */
    @GetMapping(value = "/reach.geojson", produces = "application/geo+json")
    @ResponseBody
    public Map<String, Object> reach(@RequestParam(required = false) Double km, @RequestParam(required = false) Double kmPer100m,
                                     @RequestParam(required = false) Double coastalKm) {
        return reaches.geojson(asked(km, kmPer100m, coastalKm));
    }

    private ReachRule.Rule asked(Double km, Double kmPer100m, Double coastalKm) {
        ReachRule.Rule r = rule.current();
        return km == null && kmPer100m == null && coastalKm == null ? r
                : ReachRule.Rule.of(km == null ? r.reachKm() : km, kmPer100m == null ? r.kmPer100m() : kmPer100m, coastalKm == null ? r.coastalKm() : coastalKm);
    }

    /**
     * Make the sliders' values the rule: every reach is drawn by it from now on, and it survives a restart.
     */
    @PostMapping(value = "/reach/rule", produces = "application/json")
    @ResponseBody
    public Map<String, Object> setRule(@RequestParam double km, @RequestParam double kmPer100m, @RequestParam double coastalKm) {
        ReachRule.Rule r = rule.set(km, kmPer100m, coastalKm, ConsoleModel.operatorName(), Instant.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reachKm", r.reachKm());
        out.put("kmPer100m", r.kmPer100m());
        out.put("coastalKm", r.coastalKm());
        out.put("by", rule.by());
        out.put("since", rule.since() == null ? null : rule.since().toString());
        return out;
    }

    /**
     * A point on the map: the stations whose reach contains it, and the nearest few whose does not (W-5).
     */
    /**
     * The reading at a point (W-8): what a click asks for.
     */
    @GetMapping(value = "/reading", produces = "application/json")
    @ResponseBody
    public Map<String, Object> reading(@RequestParam double lat, @RequestParam double lon, @RequestParam(defaultValue = "false") boolean force) {
        return readings.at(lat, lon, Instant.now(), force);
    }

    @GetMapping(value = "/probe", produces = "application/json")
    @ResponseBody
    public Map<String, Object> probe(@RequestParam double lat, @RequestParam double lon) {
        return probe.at(lat, lon);
    }

    /**
     * Sample one station's terrain now, ahead of the background job.
     */
    @PostMapping(value = "/terrain/{id}", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sample(@PathVariable String id) {
        return stations.station(id).map(s -> {
            boolean ok = sampler.sample(s).isPresent();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sampled", ok);
            out.put("failure", ok ? null : sampler.lastFailure());
            out.putAll(reaches.detail(id));
            return ResponseEntity.ok(out);
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * The footer's line: the Bureau's file and what is held.
     */
    @GetMapping(value = "/status.json", produces = "application/json")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bureau", status.bureau());
        out.put("held", status.held());
        return out;
    }

    /**
     * Read the Bureau's file now, whatever the timer says.
     */
    @PostMapping(value = "/bureau/read", produces = "application/json")
    @ResponseBody
    public Map<String, Object> readNow() {
        boolean downloaded = reader.read();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("downloaded", downloaded);
        out.put("bureau", status.bureau());
        out.put("held", status.held());
        return out;
    }
}
