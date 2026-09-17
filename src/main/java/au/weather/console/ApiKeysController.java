package au.weather.console;

import au.weather.access.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Create, rotate and revoke per consumer. The plaintext is shown once.
 *
 * <p>The Hub's version also granted a key the organisations it may write water ratings on behalf of
 * (D-228). There are no organisations here and nothing to write, so the grant half did not come
 * across — a key on this service is a consumer and a scope, which is what it was before D-228.
 */
@Controller
@RequestMapping("/console/api-keys")
@RequiredArgsConstructor
public class ApiKeysController {

    private final ApiKeyService keys;

    private void list(Model model) {
        model.addAttribute("keys", keys.all());
    }

    @GetMapping
    public String page(Model model) {
        list(model);
        return "api-keys";
    }

    @PostMapping
    public String create(@RequestParam String consumer, Model model) {
        ApiKeyService.Issued issued = keys.create(consumer.trim(), ConsoleModel.operatorName());
        model.addAttribute("issued", issued);
        list(model);
        return "api-keys";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable long id) {
        keys.revoke(id, ConsoleModel.operatorName());
        return "redirect:/console/api-keys";
    }
}
