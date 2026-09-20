package au.gully.console;

import au.gully.api.HexagonsController;
import au.gully.api.Reading;
import au.gully.api.Readings;
import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.States;
import au.gully.bureau.WarningsReader;
import au.gully.bureau.WindShift;
import au.gully.bureau.WindTrend;
import au.gully.science.Conditions;
import au.gully.science.WindChange;
import au.gully.upstreams.Forecast;
import au.gully.hexagons.*;
import au.gully.platform.Json;
import au.gully.upstreams.Ledger;
import au.gully.upstreams.Upstreams;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The map (docs/06 item 8): the hexagons we hold, coloured by the value you choose, with the stations,
 * a time slider, a switch between every hexagon and only the loaded ones, and a click that opens
 * everything we hold for a point. The map never triggers a fetch — it draws the pre-built layer; the
 * one thing that does is the operator's probe, which is a button, not a click.
 */
@Controller
@RequestMapping("/console/map")
@RequiredArgsConstructor
public class MapController {

    /**
     * The tessellation is drawn only when the view is small enough: past this many cells the switch
     * says so instead of drawing a smear.
     */
    static final int GRID_CELLS_MAX = 3000;

    private final HexagonStore store;
    private final MapLayer layer;
    private final StationRegistry stations;
    private final WarningsReader warnings;
    private final History history;
    private final Readings readings;
    private final Sources sources;
    private final Ledger ledger;
    private final Upstreams upstreams;
    private final Life life;
    private final Json json;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("hexagons", store.size());
        model.addAttribute("stations", stations.size());
        model.addAttribute("warnings", warnings.all().size());
        model.addAttribute("cellKm", store.grid().cellKm());
        return "map";
    }

    /**
     * The layer, console-authenticated, so the page needs no API key.
     */
    @GetMapping(value = "/layer.geojson", produces = "application/geo+json")
    @ResponseBody
    public ResponseEntity<byte[]> layer(@RequestParam(required = false) String at,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        MapLayer.Rendered r = at == null || at.isBlank() ? layer.current() : layer.at(Instant.parse(at.trim()));
        if (ifNoneMatch != null && ifNoneMatch.contains(r.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(r.etag()).build();
        }
        return ResponseEntity.ok().eTag(r.etag()).contentType(MediaType.parseMediaType("application/geo+json")).body(r.bytes());
    }

    /**
     * The tessellation inside a box: every cell, held or not, so the grid and what we hold can both
     * be seen. Empty past {@link #GRID_CELLS_MAX} cells.
     */
    @GetMapping(value = "/grid.geojson", produces = "application/geo+json")
    @ResponseBody
    public Map<String, Object> grid(@RequestParam double south, @RequestParam double west,
                                    @RequestParam double north, @RequestParam double east) {
        Grid grid = store.grid();
        List<Map<String, Object>> features = new ArrayList<>();
        List<Cell> cells = grid.within(south, west, north, east, GRID_CELLS_MAX);
        for (Cell c : cells) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("id", c.id());
            List<List<Double>> ring = new ArrayList<>();
            for (double[] v : grid.outline(c)) {
                ring.add(List.of(Math.round(v[1] * 1e5) / 1e5, Math.round(v[0] * 1e5) / 1e5));
            }
            f.put("geometry", Map.of("type", "Polygon", "coordinates", List.of(ring)));
            f.put("properties", Map.of("id", c.id(), "held", store.get(c.id()).isPresent()));
            features.add(f);
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        fc.put("meta", Map.of("cells", cells.size(), "tooMany", cells.isEmpty()));
        return fc;
    }

    @GetMapping(value = "/stations.geojson", produces = "application/geo+json")
    @ResponseBody
    public Map<String, Object> stations() {
        Instant now = Instant.now();
        List<Map<String, Object>> features = new ArrayList<>();
        int fresh = 0, shifted = 0;
        for (Station s : stations.all()) {
            Observation o = stations.latest(s.id()).orElse(null);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", s.id());
            p.put("name", s.name());
            p.put("state", s.state());
            p.put("district", s.district());
            p.put("heightM", s.heightM());
            p.put("at", o == null || o.at() == null ? null : o.at().toString());
            // Fresh as the hexagons judge it: an observation under seventy minutes old is "now"; older, or
            // none because the state's file has not been asked for lately, and the dot is drawn hollow.
            Long age = o == null || o.at() == null ? null : Duration.between(o.at(), now).toMinutes();
            p.put("ageMinutes", age);
            boolean isFresh = age != null && age < FirePictures.STATION_STALE.toMinutes();
            p.put("fresh", isFresh);
            if (isFresh) fresh++;
            p.put("temperatureC", o == null ? null : o.temperatureC());
            p.put("humidityPct", o == null ? null : o.humidityPct());
            p.put("windSpeedKmh", o == null ? null : o.windSpeedKmh());
            p.put("windDirectionDeg", o == null ? null : o.windDirectionDeg());
            p.put("windGustKmh", o == null ? null : o.windGustKmh());
            p.put("rainSince9amMm", o == null ? null : o.rainSince9amMm());
            p.put("hexagon", store.grid().cellOf(s.lat(), s.lon()).id());
            // The wind change in its last readings (W-16), and the readings themselves, newest first.
            WindShift w = stations.windShift(s.id()).orElse(null);
            p.put("windShift", w == null ? null : w.grade());
            p.put("windShiftSwing", w == null ? null : w.swingGrade());
            p.put("windShiftSpeed", w == null ? null : w.speedGrade());
            p.put("windShiftDeg", w == null ? null : w.swingDeg());
            p.put("windShiftKmh", w == null ? null : w.deltaKmh());
            p.put("windShiftFromDeg", w == null ? null : w.fromDeg());
            p.put("windShiftMinutes", w == null ? null : w.overMinutes());
            p.put("windShiftText", w == null ? null : w.describe());
            if (w != null) shifted++;
            // The trend the map draws beside the change: the mean of the readings before the latest, the
            // latest, and - from the station's hexagon - the model's wind an hour ahead and the change it expects.
            WindTrend t = stations.windTrend(s.id()).orElse(null);
            p.put("windMeanDeg", t == null ? null : t.meanDeg());
            p.put("windMeanKmh", t == null ? null : t.meanKmh());
            p.put("windMeanGustKmh", t == null ? null : t.meanGust());
            p.put("windMeanOver", t == null ? null : t.readings());
            p.put("windMeanMinutes", t == null ? null : t.overMinutes());
            p.put("windTrendSwingDeg", t == null ? null : t.swingDeg());
            p.put("windTrendDeltaKmh", t == null ? null : t.deltaKmh());
            forecastWind(p, store.get((String) p.get("hexagon")).orElse(null), now);
            List<List<Object>> recent = new ArrayList<>();
            for (Observation r : stations.recent(s.id())) {
                recent.add(java.util.Arrays.asList(r.at() == null ? null : r.at().toString(), r.temperatureC(), r.humidityPct(), r.windSpeedKmh(), r.windDirectionDeg(), r.windGustKmh()));
            }
            p.put("recent", recent);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("id", s.id());
            f.put("geometry", Map.of("type", "Point", "coordinates", List.of(s.lon(), s.lat())));
            f.put("properties", p);
            features.add(f);
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        fc.put("meta", Map.of("stations", features.size(), "fresh", fresh, "windShifts", shifted));
        return fc;
    }

    /**
     * The hours ahead the station point carries: the model's wind at each, read off the hexagon's
     * hourly series, so the map can draw where the wind is going beside where it has been.
     */
    static final int[] AHEAD_HOURS = {1, 3, 6};

    /**
     * The model's wind for the station's hexagon: an hour, three and six ahead, and the wind change
     * the forecast expects when it is still to come. Nulls where the hexagon holds no forecast.
     */
    static void forecastWind(Map<String, Object> p, Hexagon h, Instant now) {
        Forecast fc = h == null ? null : h.forecast();
        for (int hours : AHEAD_HOURS) {
            Conditions c = fc == null ? null : fc.at(now.plus(Duration.ofHours(hours)));
            p.put("fc" + hours + "hWindDeg", c == null ? null : c.windDirectionDeg());
            p.put("fc" + hours + "hWindKmh", c == null ? null : c.windSpeedKmh());
            p.put("fc" + hours + "hGustKmh", c == null ? null : c.windGustKmh());
        }
        WindChange change = h == null || h.fire() == null || h.fire().wind() == null ? null : h.fire().wind().change();
        boolean ahead = change != null && change.at() != null && change.at().isAfter(now);
        p.put("fcChangeAt", ahead ? change.at().toString() : null);
        p.put("fcChangeFromDeg", ahead ? change.fromDeg() : null);
        p.put("fcChangeToDeg", ahead ? change.toDeg() : null);
        p.put("fcChangeKmh", ahead ? change.speedKmh() : null);
        p.put("fcChangeGustKmh", ahead ? change.gustKmh() : null);
        p.put("fcChangeInMinutes", ahead ? Duration.between(now, change.at()).toMinutes() : null);
    }

    /**
     * The sources as they stand and the last reads they saw (W-14): each with its cadence, when an ask
     * last checked it and last read it, what it holds, and the hexagon whose ask caused that; then
     * the ledger's latest rows - station files, warnings, CFS feeds, forecasts, elevations, land
     * cover - each against the hexagon it was read for. What the map's sources panel shows.
     */
    @GetMapping(value = "/sources.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> sources() {
        Instant now = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now.toString());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Sources.Status st : sources.status(now)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", st.id());
            m.put("name", st.name());
            m.put("cadenceMinutes", st.cadence().toMinutes());
            m.put("checkedAt", st.checkedAt() == null ? null : st.checkedAt().toString());
            m.put("readAt", st.readAt() == null ? null : st.readAt().toString());
            m.put("dueAt", st.dueAt() == null ? null : st.dueAt().toString());
            m.put("items", st.items());
            m.put("failure", st.failure());
            m.put("triggeredBy", st.triggeredBy());
            m.put("triggeredAt", st.triggeredAt() == null ? null : st.triggeredAt().toString());
            list.add(m);
        }
        out.put("sources", list);
        List<Map<String, Object>> reads = new ArrayList<>();
        for (Map<String, Object> row : ledger.recent(60)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", row.get("upstream"));
            Object at = row.get("at");
            m.put("at", at == null ? null : at.toString());
            m.put("ok", row.get("ok"));
            m.put("ms", row.get("latency_ms"));
            m.put("units", row.get("units"));
            m.put("detail", row.get("detail"));
            reads.add(m);
        }
        out.put("reads", reads);
        Map<String, Object> allowance = new LinkedHashMap<>();
        allowance.put("dayFraction", Math.round(upstreams.dayFraction() * 1000) / 1000.0);
        allowance.put("lifeMinutes", life.forecast().toMinutes());
        out.put("allowance", allowance);
        return out;
    }

    /**
     * Everything held for one hexagon, for the click.
     */
    @GetMapping(value = "/hexagon/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> hexagon(@PathVariable String id) {
        Optional<Hexagon> held = store.get(id);
        if (held.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Hexagon h = held.get();
        Map<String, Object> out = new LinkedHashMap<>(HexagonsController.row(h, Instant.now(), store));
        out.put("reading", readings.of(h, new Reading.Point(h.cell().lat(), h.cell().lon()), true));
        out.put("drought", h.drought());
        out.put("river", h.river() == null ? null : h.river().river(LocalDate.now(store.zoneOf(h))));
        out.put("history", history.of(id, 24));
        out.put("historyCount", history.countFor(id));
        out.put("ledger", h.stationId() == null ? List.of() : stations.recentSamples(h.stationId(), 12));
        return ResponseEntity.ok(out);
    }

    /**
     * A map left open is a request (W-14): every minute it says which states it is looking at, and the
     * station files and warnings of those states are read when they are due - the same conditional
     * GETs an ask would make, at the same cadence, and nothing else. Nothing is fetched from a model.
     */
    @PostMapping(value = "/watch", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> watch(@RequestParam double south, @RequestParam double west, @RequestParam double north, @RequestParam double east) {
        java.util.Set<String> states = new java.util.LinkedHashSet<>();
        for (double[] p : new double[][]{{south, west}, {south, east}, {north, west}, {north, east}, {(south + north) / 2, (west + east) / 2}}) {
            if (Geo.plausible(p[0], p[1])) {
                states.addAll(States.covering(p[0], p[1]));
            }
        }
        List<String> read = sources.ensureStates(states, Instant.now(), "the console map");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("states", states);
        out.put("read", read);
        return out;
    }

    /**
     * The operator's probe: an ask at a point, which creates the hexagon and fetches its reading.
     * POST, because it spends allowance and must not be repeated by a refresh.
     */
    @PostMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Reading> probe(@RequestParam double lat, @RequestParam double lon) {
        if (!Geo.plausible(lat, lon) || !Geo.inAustralia(lat, lon)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(readings.now(lat, lon, true, null));
    }
}
