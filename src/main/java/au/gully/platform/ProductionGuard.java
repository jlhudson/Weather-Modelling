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
 * Under the {@code production} profile the application refuses to start while a secret is blank or still
 * the value a fresh checkout runs with. The message names the settings, never their values.
 */
@Component
@Profile("production")
public class ProductionGuard {

    /**
     * Each property, and the values it may not hold in production.
     */
    static final Map<String, Set<String>> REFUSED = refusals();

    /**
     * The keys the applications hold for each other. Old development files carried them as {@code dev-key-…}
     * values that match across the five checkouts, so a development machine talks to itself out of the
     * box; production must replace each with a value of its own.
     */
    static final List<String> KEYS = List.of("gully.keys.hub", "gully.hub.api-key");
    static final String DEV_KEY_PREFIX = "dev-key-";

    public ProductionGuard(Environment environment) {
        List<String> refused = new ArrayList<>(refused(environment::getProperty, REFUSED));
        refused.addAll(devKeys(environment::getProperty, KEYS));
        if (!refused.isEmpty()) {
            throw new IllegalStateException("the production profile refuses to start: " + String.join(", ", refused)
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

    /**
     * The key properties still holding a {@code dev-key-} value, in order. Blank is not
     * refused here: a blank key means "not shared", which production may choose.
     */
    static List<String> devKeys(Function<String, String> read, List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            String v = read.apply(key);
            if (v != null && v.contains(DEV_KEY_PREFIX)) {
                out.add(key + " (a leftover development key)");
            }
        }
        return out;
    }
}
