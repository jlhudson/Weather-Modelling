package au.gully.console;

import au.gully.api.HexagonsController;
import au.gully.hexagons.Hexagon;
import au.gully.hexagons.HexagonStore;
import au.gully.hexagons.History;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The hexagons page (docs/06 item 8): every hexagon we hold, one row each, sortable by state, age
 * and next refresh in the browser.
 */
@Controller
@RequestMapping("/console/hexagons")
@RequiredArgsConstructor
public class HexagonsPageController {

    private final HexagonStore store;
    private final History history;

    @GetMapping
    public String page(Model model) {
        Instant now = Instant.now();
        List<Map<String, Object>> rows = store.all().stream()
                .sorted(Comparator.comparing((Hexagon h) -> h.lastAskedAt() == null ? Instant.EPOCH : h.lastAskedAt()).reversed())
                .map(h -> HexagonsController.row(h, now, store)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("active", store.all().stream().filter(Hexagon::active).count());
        model.addAttribute("withStation", store.all().stream().filter(Hexagon::hasStation).count());
        model.addAttribute("withForecast", store.all().stream().filter(Hexagon::hasForecast).count());
        model.addAttribute("snapshots", history.count());
        model.addAttribute("served", store.servedCount());
        model.addAttribute("fetched", store.fetchedCount());
        model.addAttribute("stale", store.staleCount());
        return "hexagons";
    }

    /**
     * Drops cold forecasts now rather than on the hour. An operator action, not a timer.
     */
    @PostMapping("/sweep")
    public String sweep() {
        store.sweep();
        return "redirect:/console/hexagons";
    }
}
