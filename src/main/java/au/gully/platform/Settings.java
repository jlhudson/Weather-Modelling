package au.gully.platform;

import au.gully.hexagons.Grid;
import au.gully.platform.diagnostics.DiagnosticsProperties;
import au.gully.terrain.TerrainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every effective setting, printed once at startup (docs/06 item 10), so what a deployment is
 * actually running with is in the log rather than inferred from three files.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Settings {

    private final GullyProperties gully;
    private final TerrainProperties terrain;
    private final DiagnosticsProperties diagnostics;

    public Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gully.enabled", gully.enabled());
        m.put("gully.contact", gully.contact());
        m.put("gully.zone", gully.zone());
        m.put("gully.refresh-ahead", gully.refreshAhead());
        m.put("gully.cold-after", gully.coldAfter());
        m.put("gully.upstreams.order", gully.upstreams().order());
        m.put("gully.sources.bureau", gully.sources().bureau());
        m.put("gully.sources.cfs", gully.sources().cfs());
        m.put("gully.sources.rivers", gully.sources().rivers());
        m.put("gully.history.backups", gully.history().backups());
        m.put("gully.console.lockout-after", gully.console().lockoutAfter());
        m.put("gully.console.lockout-for", gully.console().lockoutFor());
        m.put("gully.api.cors-origins", gully.api().corsOrigins());
        m.put("gully.api.requests-per-minute-per-key", gully.api().requestsPerMinutePerKey());
        m.put("gully.api.requests-per-day-per-key", gully.api().requestsPerDayPerKey());
        m.put("gully.terrain.elevation-file", terrain.elevationFile());
        m.put("gully.terrain.land-cover-file", terrain.landCoverFile());
        m.put("gully.diagnostics.keep-errors", diagnostics.keepErrors());
        m.put("gully.diagnostics.keep-warnings", diagnostics.keepWarnings());
        m.put("grid.cell-km (constant)", Grid.CELL_KM);
        m.put("grid.anchor (constant)", Grid.ANCHOR_LAT + "," + Grid.ANCHOR_LON);
        m.put("grid.station-reach-km (default; the console may hold another, read with the registers)", Grid.DEFAULT_STATION_REACH_KM);
        m.put("drought.rings (default; the console may hold another)", au.gully.drought.DroughtRule.DEFAULT_RINGS);
        m.put("drought.km-per-100m (default; the console may hold another)", au.gully.drought.DroughtRule.DEFAULT_KM_PER_100M);
        return m;
    }

    public void print() {
        StringBuilder sb = new StringBuilder("settings in force:");
        all().forEach((k, v) -> sb.append("\n  ").append(k).append(" = ").append(v));
        log.info(sb.toString());
    }
}
