package au.gully.console;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationsFeed;
import au.gully.platform.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The map: the Bureau's stations as points, each with what it last said, and a click that opens
 * everything held for one. The feeds are the API's own shapes; the map draws what a caller gets.
 */
@Controller
@RequestMapping("/console/map")
@RequiredArgsConstructor
public class MapController {

    private final StationsFeed feed;
    private final StationReader reader;
    private final Status status;

    @GetMapping
    public String page(Model model) {
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
        return feed.detail(id).map(ResponseEntity::ok).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
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
