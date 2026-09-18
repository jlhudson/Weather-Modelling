package au.gully.console;

import au.gully.cfs.Curing;
import au.gully.cfs.Ratings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/**
 * Grass curing per fire ban district, entered weekly in fire season from the CFS's map (docs/06
 * item 18), beside the district's published rating so the two can be read together.
 */
@Controller
@RequestMapping("/console/curing")
@RequiredArgsConstructor
public class CuringController {

    private final Curing curing;
    private final Ratings ratings;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("entries", curing.all());
        model.addAttribute("ratings", ratings.all().stream().collect(java.util.stream.Collectors.toMap(
                r -> Ratings.normalise(r.district()), r -> r.at(java.time.Instant.now()).orElse(null),
                (a, b) -> a, java.util.LinkedHashMap::new)));
        model.addAttribute("ratingsReadAt", ratings.readAt());
        model.addAttribute("today", LocalDate.now());
        return "curing";
    }

    @PostMapping
    public String save(@RequestParam String district, @RequestParam int percent,
                       @RequestParam(required = false) String enteredOn, @RequestParam(required = false) String source,
                       RedirectAttributes flash) {
        LocalDate on = enteredOn == null || enteredOn.isBlank() ? LocalDate.now() : LocalDate.parse(enteredOn.trim());
        Curing.Entry e = curing.save(district, percent, on, source, ConsoleModel.operatorName());
        flash.addFlashAttribute("message", e.district() + " set to " + e.percent() + "% (entered " + e.enteredOn() + ")");
        return "redirect:/console/curing";
    }

    @PostMapping("/clear")
    public String clear(@RequestParam String district, RedirectAttributes flash) {
        curing.clear(district, ConsoleModel.operatorName());
        flash.addFlashAttribute("message", district + " cleared");
        return "redirect:/console/curing";
    }
}
