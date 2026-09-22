package au.gully.console;

import au.gully.platform.Housekeeping;
import au.gully.platform.Reset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The admin page (W-18), for testing: what the weather tables hold, and one button that deletes it
 * all and starts the service again - the Bureau's file read at once, the terrain and the year of
 * record by the housekeeping in the background.
 */
@Controller
@RequestMapping("/console/admin")
@RequiredArgsConstructor
public class AdminController {

    private final Reset reset;
    private final Housekeeping housekeeping;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("rows", reset.rows());
        model.addAttribute("lastReset", reset.last());
        model.addAttribute("lastHousekeeping", housekeeping.last());
        return "admin";
    }

    @PostMapping("/reset")
    public String reset() {
        reset.run(ConsoleModel.operatorName());
        return "redirect:/console/admin";
    }
}
