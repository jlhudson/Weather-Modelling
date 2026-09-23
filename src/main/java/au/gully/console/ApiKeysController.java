package au.gully.console;

import au.gully.platform.access.ApiKey;
import au.gully.platform.access.ApiKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Create, rotate and revoke per consumer, with a scope. The plaintext is shown once.
 */
@Controller
@RequestMapping("/console/api-keys")
@RequiredArgsConstructor
public class ApiKeysController {

    private final ApiKeys keys;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("keys", keys.all());
        model.addAttribute("scopes", ApiKey.Scope.values());
        return "api-keys";
    }

    @PostMapping
    public String create(@RequestParam String consumer, @RequestParam(defaultValue = "ALL") String scope, Model model) {
        ApiKey.Scope s = ApiKey.Scope.parse(scope);
        if (s == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "no such scope: " + scope);
        }
        ApiKeys.Issued issued = keys.create(consumer.trim(), s, ConsoleModel.operatorName());
        model.addAttribute("issued", issued);
        model.addAttribute("keys", keys.all());
        model.addAttribute("scopes", ApiKey.Scope.values());
        return "api-keys";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable long id) {
        keys.revoke(id, ConsoleModel.operatorName());
        return "redirect:/console/api-keys";
    }
}
