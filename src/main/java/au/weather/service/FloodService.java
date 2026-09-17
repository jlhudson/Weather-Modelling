package au.weather.service;

import au.weather.geo.Geo;
import au.weather.json.Json;
import au.weather.http.HostLimiter;
import au.weather.terrain.ElevationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static au.weather.core.Numbers.round2;

/**
 * River discharge from GloFAS, cached per small area per day.
 * <p>
 * Rain and ground saturation come free with the weather report and the drought spin-up. This is the
 * part of the flood picture that has to be fetched: how much water is actually in the river, modelled
 * at 5 km resolution and forecast well past the weather.
 * <p>
 * <strong>The raw figure is not the fact.</strong> Four hundred cubic metres a second means nothing
 * without knowing whether that river usually runs at twenty or at two thousand, so a recent baseline is
 * fetched alongside the forecast and the ratio travels with the value. The baseline is the mean over
 * the past window, which is a seasonal figure rather than a climatological one, and it says so.
 * <p>
 * The reuse radius is tight, unlike everything else here. Discharge belongs to a particular
 * watercourse, and GloFAS answers for the largest river within about 5 km; reusing one cell across
 * fifty kilometres would confidently report a different river.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FloodService {

    private static final int BASELINE_DAYS = 92;

    private final OpenMeteoDailyClient client;
    private final WeatherRepositories.RiverCellRepository cells;
    private final WeatherBudget budget;
    private final HostLimiter hostLimiter;
    private final ElevationService elevation;
    private final WeatherProperties properties;
    private final WeatherGovernor governor;
    private final Json json;
    private final Map<UUID, Cell> live = new ConcurrentHashMap<>();

    /**
     * Rising, steady or falling over the forecast. A twenty per cent band, because GloFAS is a model at
     * 5 km and a two per cent difference is not a trend, it is arithmetic.
     */
    private static String trend(Double current, Double peakAhead) {
        if (current == null || peakAhead == null) {
            return null;
        }
        if (peakAhead > current * 1.2) return "RISING";
        if (peakAhead < current * 0.8) return "FALLING";
        return "STEADY";
    }

    private static Double round(Double v) {
        return v == null ? null : round2(v);
    }

    /**
     * The river at a point, or empty when discharge is disabled, out of allowance, or unmodelled here.
     */
    public Optional<River> at(double lat, double lon, ZoneId zone) {
        WeatherProperties.Flood cfg = properties.flood();
        if (!cfg.enabled() || !cfg.dischargeEnabled()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(zone);
        Optional<Cell> nearby = live.values().stream()
                .filter(c -> c.computedFor().equals(today))
                .filter(c -> Geo.planarMetres(lat, lon, c.lat(), c.lon()) <= governor.tuning().riverCellRadiusMetres())
                .min(Comparator.comparingDouble(c -> Geo.planarMetres(lat, lon, c.lat(), c.lon())));
        if (nearby.isPresent()) {
            return nearby.get().hasRiver() ? Optional.of(river(nearby.get().series(), today)) : Optional.empty();
        }
        return fetch(lat, lon, today, cfg);
    }

    /**
     * The river at a point <strong>only if a cell already covers it</strong> — never a fetch.
     * <p>
     * The mirror of {@link DroughtService#cached}, and there for the same caller: a map layer on a
     * sixty-second refresh, drawing every anchor at once with nobody having asked about any particular
     * point. Empty means "no cell here yet", which on a map is a gap worth seeing rather than a reason to
     * spend allowance.
     */
    public Optional<River> cached(double lat, double lon, ZoneId zone) {
        WeatherProperties.Flood cfg = properties.flood();
        if (!cfg.enabled() || !cfg.dischargeEnabled()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(zone);
        return live.values().stream()
                .filter(c -> c.computedFor().equals(today))
                .filter(c -> Geo.planarMetres(lat, lon, c.lat(), c.lon()) <= governor.tuning().riverCellRadiusMetres())
                .min(Comparator.comparingDouble(c -> Geo.planarMetres(lat, lon, c.lat(), c.lon())))
                .filter(Cell::hasRiver)
                .map(c -> river(c.series(), today));
    }

    private Optional<River> fetch(double lat, double lon, LocalDate today, WeatherProperties.Flood cfg) {
        WeatherBudget.Decision decision = budget.check(DroughtService.BUDGET, cfg.callWeight());
        if (!decision.allowed()) {
            log.debug("river discharge at {},{} skipped: {}", lat, lon, decision.reason()); // the budget said why, once, when it decided
            return Optional.empty();
        }
        long started = System.nanoTime();
        try {
            hostLimiter.acquire(URI.create(WeatherProperties.Flood.ENDPOINT).getHost());
            List<OpenMeteoDailyClient.DischargeRow> rows =
                    client.discharge(WeatherProperties.Flood.ENDPOINT, lat, lon, BASELINE_DAYS, cfg.forecastDays());
            budget.record(DroughtService.BUDGET, cfg.callWeight(), true,
                    Duration.ofNanos(System.nanoTime() - started), "river discharge");

            // Every value null is GloFAS saying there is no modelled river here. That is an answer.
            boolean hasRiver = rows.stream().anyMatch(r -> r.cumecs() != null);
            store(lat, lon, today, rows, hasRiver);
            if (!hasRiver) {
                log.debug("no modelled river within 5 km of {},{}", lat, lon);
                return Optional.empty();
            }
            return Optional.of(river(rows, today));
        } catch (Exception e) {
            budget.record(DroughtService.BUDGET, cfg.callWeight(), false,
                    Duration.ofNanos(System.nanoTime() - started), e.getMessage());
            log.warn("river discharge at {},{} failed: {}", lat, lon, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Turns a raw series into the three numbers worth reading: now, the baseline, and where it is going.
     */
    private River river(List<OpenMeteoDailyClient.DischargeRow> rows, LocalDate today) {
        Double current = null;
        double baselineTotal = 0;
        int baselineCount = 0;
        Double peakAhead = null;
        List<OpenMeteoDailyClient.DischargeRow> ahead = new ArrayList<>();
        for (OpenMeteoDailyClient.DischargeRow row : rows) {
            if (row.cumecs() == null) {
                continue;
            }
            if (row.date().isBefore(today)) {
                baselineTotal += row.cumecs();
                baselineCount++;
                continue;
            }
            // Today belongs in the outlook as well as in `current`, or the first row of every flood
            // forecast is blank for no reason a reader could work out.
            ahead.add(row);
            if (row.date().equals(today)) {
                current = row.cumecs();
            } else if (peakAhead == null || row.cumecs() > peakAhead) {
                peakAhead = row.cumecs();
            }
        }
        Double mean = baselineCount == 0 ? null : baselineTotal / baselineCount;
        Double ratio = current == null || mean == null || mean == 0 ? null : round(current / mean);
        String trend = trend(current, peakAhead);
        return new River(round(current), round(mean), ratio, trend, ahead);
    }

    /**
     * Samples the terrain height and then writes the row. Split the same way {@code WeatherCache.store} is,
     * and for the same reason: the tile fetch must not happen while a database connection is held.
     */
    void store(double lat, double lon, LocalDate today, List<OpenMeteoDailyClient.DischargeRow> rows, boolean hasRiver) {
        OptionalDouble height = elevation.at(lat, lon);
        persist(lat, lon, height.isPresent() ? height.getAsDouble() : null, today, rows, hasRiver);
    }

    @Transactional
    void persist(double lat, double lon, Double terrainM, LocalDate today,
                 List<OpenMeteoDailyClient.DischargeRow> rows, boolean hasRiver) {
        List<Map<String, Object>> serialised = new ArrayList<>();
        for (OpenMeteoDailyClient.DischargeRow row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", row.date().toString());
            m.put("cumecs", row.cumecs());
            serialised.add(m);
        }
        RiverCellEntity entity = new RiverCellEntity();
        entity.setId(UUID.randomUUID());
        entity.setLat(lat);
        entity.setLon(lon);
        entity.setGeom(Geo.point(lat, lon));
        entity.setDischarge(json.write(serialised));
        entity.setHasRiver(hasRiver);
        entity.setComputedFor(today);
        entity.setTerrainM(terrainM);
        entity.setUpdatedAt(Instant.now());
        cells.save(entity);
        live.put(entity.getId(), new Cell(entity.getId(), lat, lon, terrainM, today, hasRiver, rows));
    }

    /**
     * Phase 2: a day's discharge series is still a day's discharge series after a restart.
     */
    public void rehydrate() {
        live.clear();
        LocalDate from = LocalDate.now(properties.zoneId()).minusDays(1);
        for (RiverCellEntity row : cells.findByComputedForGreaterThanEqual(from)) {
            live.put(row.getId(), new Cell(row.getId(), row.getLat(), row.getLon(), row.getTerrainM(),
                    row.getComputedFor(), row.isHasRiver(), deserialise(row.getDischarge())));
        }
        log.info("river cells rehydrated: {}", live.size());
    }

    @Transactional
    public int sweep() {
        LocalDate keepFrom = LocalDate.now(properties.zoneId()).minusDays(1);
        List<UUID> gone = live.values().stream()
                .filter(c -> c.computedFor().isBefore(keepFrom))
                .map(Cell::id)
                .toList();
        gone.forEach(live::remove);
        cells.deleteByComputedForBefore(keepFrom.minusDays(6));
        return gone.size();
    }

    public List<Cell> cells() {
        return List.copyOf(live.values());
    }

    @SuppressWarnings("unchecked")
    private List<OpenMeteoDailyClient.DischargeRow> deserialise(String stored) {
        List<OpenMeteoDailyClient.DischargeRow> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        try {
            List<Map<String, Object>> rows = json.read(stored, List.class);
            for (Map<String, Object> row : rows) {
                Object date = row.get("date");
                Object value = row.get("cumecs");
                if (date != null) {
                    out.add(new OpenMeteoDailyClient.DischargeRow(LocalDate.parse(date.toString()),
                            value instanceof Number n ? n.doubleValue() : null));
                }
            }
        } catch (RuntimeException e) {
            log.debug("discarding unreadable river series: {}", e.getMessage());
        }
        return out;
    }

    /**
     * @param ratioToMean discharge against its own recent mean; the number that means something
     * @param ahead       the forecast days, for the flood outlook
     */
    public record River(Double currentCumecs, Double meanCumecs, Double ratioToMean, String trend,
                        List<OpenMeteoDailyClient.DischargeRow> ahead) {
    }

    /**
     * @param terrainM true ground height under the cell, or null when terrain is off or the tile is missing
     */
    public record Cell(UUID id, double lat, double lon, Double terrainM, LocalDate computedFor, boolean hasRiver,
                       List<OpenMeteoDailyClient.DischargeRow> series) {
    }
}
