package au.weather.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The three routes that are not a page of their own: the two that land on the console's one real page,
 * and the login form.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/console/weather";
    }

    @GetMapping("/console")
    public String console() {
        return "redirect:/console/weather";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
