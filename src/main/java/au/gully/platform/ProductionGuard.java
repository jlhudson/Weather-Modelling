package au.gully.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Under the {@code prod} profile the application refuses to start while a secret is blank or still
 * the value a fresh checkout runs with. The message names the settings, never their values.
 */
@Component
@Profile("prod")
public class ProductionGuard {

    /**
     * Each property, and the values it may not hold in production.
     */
    static final Map<String, Set<String>> REFUSED = refusals();

    public ProductionGuard(Environment environment) {
        List<String> refused = refused(environment::getProperty, REFUSED);
        if (!refused.isEmpty()) {
            throw new IllegalStateException("the prod profile refuses to start: " + String.join(", ", refused)
                    + " blank or left at the default; set them in the deployment's .env");
        }
    }

    private static Map<String, Set<String>> refusals() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("gully.console.code", Set.of("", "12345678"));
        m.put("spring.datasource.password", Set.of("", "change-me"));
        return m;
    }

    /**
     * The properties whose value is refused, in order.
     */
    static List<String> refused(Function<String, String> read, Map<String, Set<String>> refusals) {
        List<String> out = new ArrayList<>();
        refusals.forEach((property, values) -> {
            String v = read.apply(property);
            if (v == null || values.contains(v.trim())) {
                out.add(property);
            }
        });
        return out;
    }
}
