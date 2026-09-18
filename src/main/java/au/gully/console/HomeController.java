package au.gully.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The routes that are not a page of their own: the two that land on the map, and the login form.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/console/map";
    }

    @GetMapping("/console")
    public String console() {
        return "redirect:/console/map";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
