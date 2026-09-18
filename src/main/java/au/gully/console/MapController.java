package au.gully.console;

import au.gully.api.HexagonsController;
import au.gully.api.Reading;
import au.gully.api.Readings;
import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.WarningsReader;
import au.gully.hexagons.*;
import au.gully.platform.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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
        List<Map<String, Object>> features = new ArrayList<>();
        for (Station s : stations.all()) {
            Observation o = stations.latest(s.id()).orElse(null);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", s.id());
            p.put("name", s.name());
            p.put("district", s.district());
            p.put("heightM", s.heightM());
            p.put("at", o == null || o.at() == null ? null : o.at().toString());
            p.put("temperatureC", o == null ? null : o.temperatureC());
            p.put("humidityPct", o == null ? null : o.humidityPct());
            p.put("windSpeedKmh", o == null ? null : o.windSpeedKmh());
            p.put("windDirectionDeg", o == null ? null : o.windDirectionDeg());
            p.put("windGustKmh", o == null ? null : o.windGustKmh());
            p.put("rainSince9amMm", o == null ? null : o.rainSince9amMm());
            p.put("hexagon", store.grid().cellOf(s.lat(), s.lon()).id());
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
        return fc;
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
        Map<String, Object> out = new LinkedHashMap<>(HexagonsController.row(h, Instant.now()));
        out.put("reading", readings.of(h, new Reading.Point(h.cell().lat(), h.cell().lon()), true));
        out.put("drought", h.drought());
        out.put("river", h.river() == null ? null : h.river().river(LocalDate.now(store.zoneOf(h))));
        out.put("history", history.of(id, 24));
        out.put("historyCount", history.countFor(id));
        out.put("ledger", h.stationId() == null ? List.of() : stations.recentSamples(h.stationId(), 12));
        return ResponseEntity.ok(out);
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
