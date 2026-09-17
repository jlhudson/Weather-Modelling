package au.weather.console;

import au.weather.diagnostics.DiagnosticsLayer;
import au.weather.diagnostics.DiagnosticsProperties;
import au.weather.diagnostics.LogEventStore;
import au.weather.diagnostics.StartupHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The diagnostics layer as a page (D-234): what the morning agent reads, for the operator who wants
 * to see it without a key — the startup record, the weather block, and every warning and error
 * signature with its count, its latest line and its trace, with the clear the agent has. The
 * {@code .json} reads beneath it are the API's own payloads, so what the agent is handed can be read
 * in a browser tab.
 *
 * <p>The Hub's {@code sources.json} and {@code source.json} went with the source register.
 */
@Controller
@RequestMapping("/console/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsConsoleController {

    /**
     * The windows the page offers; the API takes any duration.
     */
    private static final Map<String, String> WINDOWS = new LinkedHashMap<>();
    private static final ConsoleModel.Fmt FMT = new ConsoleModel.Fmt();

    static {
        WINDOWS.put("PT1H", "last hour");
        WINDOWS.put("PT6H", "last 6 hours");
        WINDOWS.put("PT24H", "last 24 hours");
        WINDOWS.put("P2D", "last 2 days");
        WINDOWS.put("P7D", "last 7 days");
    }

    private final DiagnosticsLayer layer;
    private final LogEventStore logs;
    private final DiagnosticsProperties properties;
    private final StartupHistory startup;

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Duration window(String window) {
        if (window == null || window.isBlank()) {
            return properties.window();
        }
        try {
            Duration d = Duration.parse(window.trim());
            return d.isNegative() || d.isZero() ? properties.window() : d.compareTo(Duration.ofDays(30)) > 0 ? Duration.ofDays(30) : d;
        } catch (RuntimeException e) {
            return properties.window();
        }
    }

    @GetMapping
    public String page(@RequestParam(required = false) String window,
                       @RequestParam(required = false) String level,
                       @RequestParam(required = false) String source,
                       Model model) {
        Duration w = window(window);
        model.addAttribute("summary", layer.summary(w));
        model.addAttribute("events", logs.recent(blank(level) == null ? null : level.trim().toUpperCase(), blank(source), Instant.now().minus(w), 300));
        model.addAttribute("window", w.toString());
        model.addAttribute("windows", WINDOWS);
        model.addAttribute("uptime", FMT.human(Duration.between(startup.startedAt(), Instant.now())));
        model.addAttribute("level", blank(level));
        model.addAttribute("source", blank(source));
        model.addAttribute("retention", properties);
        return "diagnostics";
    }

    // ---------------------------------------------------------------- the API's payloads, console-authenticated

    @GetMapping(value = "/summary.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> summaryJson(@RequestParam(required = false) String window) {
        return layer.summary(window(window));
    }

    @GetMapping(value = "/logs.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> logsJson(@RequestParam(required = false) String level,
                                        @RequestParam(required = false) String source,
                                        @RequestParam(required = false) String window,
                                        @RequestParam(defaultValue = "200") int limit) {
        return layer.logs(blank(level) == null ? null : level.trim().toUpperCase(), blank(source), window(window), limit);
    }

    // ---------------------------------------------------------------- the clear

    @PostMapping("/logs/clear")
    public String clear(@RequestParam(required = false) String level,
                        @RequestParam(required = false) String source,
                        @RequestParam(required = false) String before,
                        @RequestParam(required = false) String window,
                        RedirectAttributes flash) {
        Instant bound = blank(before) == null ? null : Instant.parse(before.trim());
        int cleared = layer.clear(blank(level) == null ? null : level.trim().toUpperCase(), blank(source), bound, ConsoleModel.operatorName());
        flash.addFlashAttribute("cleared", cleared);
        return "redirect:/console/diagnostics" + (blank(window) == null ? "" : "?window=" + window.trim());
    }

    @PostMapping("/logs/{id}/clear")
    public String clear(@PathVariable long id, @RequestParam(required = false) String window, RedirectAttributes flash) {
        flash.addFlashAttribute("cleared", layer.clear(id, ConsoleModel.operatorName()) ? 1 : 0);
        return "redirect:/console/diagnostics" + (blank(window) == null ? "" : "?window=" + window.trim());
    }
}
