package au.gully.platform;

import au.gully.platform.diagnostics.DiagnosticsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every effective setting, printed once at startup, so what a deployment is actually running with
 * is in the log rather than inferred from three files.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Settings {

    private final GullyProperties gully;
    private final DiagnosticsProperties diagnostics;

    public Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gully.enabled", gully.enabled());
        m.put("gully.contact", gully.contact());
        m.put("gully.zone", gully.zone());
        m.put("gully.upstreams.order", gully.upstreams().order());
        m.put("gully.console.lockout-after", gully.console().lockoutAfter());
        m.put("gully.console.lockout-for", gully.console().lockoutFor());
        m.put("gully.api.cors-origins", gully.api().corsOrigins());
        m.put("gully.api.requests-per-minute-per-key", gully.api().requestsPerMinutePerKey());
        m.put("gully.api.requests-per-day-per-key", gully.api().requestsPerDayPerKey());
        m.put("gully.diagnostics.keep-errors", diagnostics.keepErrors());
        m.put("gully.diagnostics.keep-warnings", diagnostics.keepWarnings());
        return m;
    }

    public void print() {
        StringBuilder sb = new StringBuilder("settings in force:");
        all().forEach((k, v) -> sb.append("\n  ").append(k).append(" = ").append(v));
        log.info(sb.toString());
    }
}
