package au.gully.console;

import au.gully.cfs.Curing;
import au.gully.cfs.FireBan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The curing page (W-24): each fire ban district's grass curing and fuel load, entered from the CFS's weekly
 * curing map, beside what the CFS has published for the district today.
 */
@Controller
@RequestMapping("/console/curing")
@RequiredArgsConstructor
public class CuringController {

    private final Curing curing;
    private final FireBan fireBan;

    @GetMapping
    public String page(Model model) {
        LocalDate today = LocalDate.now(ZoneId.of("Australia/Adelaide"));
        model.addAttribute("entries", curing.all());
        model.addAttribute("today", today);
        model.addAttribute("published", fireBan.today(Instant.now()));
        return "curing";
    }

    @PostMapping
    public String save(@RequestParam String district, @RequestParam int percent, @RequestParam(required = false) Double fuelLoad,
                       @RequestParam(required = false) LocalDate enteredOn, @RequestParam(required = false) String source, RedirectAttributes flash) {
        Curing.Entry e = curing.save(district, percent, fuelLoad, enteredOn, source, ConsoleModel.operatorName());
        flash.addFlashAttribute("message", e.district() + ": " + e.percent() + "% curing, " + e.fuelLoadTHa() + " t/ha, for " + e.enteredOn());
        return "redirect:/console/curing";
    }

    @PostMapping("/clear")
    public String clear(@RequestParam String district, RedirectAttributes flash) {
        curing.clear(district, ConsoleModel.operatorName());
        flash.addFlashAttribute("message", district + ": cleared");
        return "redirect:/console/curing";
    }
}
