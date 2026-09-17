package au.weather.service;

import au.weather.geo.Geo;
import au.weather.core.DroughtIndex;
import au.weather.core.Kbdi;
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

import static au.weather.core.Numbers.round1;

/**
 * The soil moisture deficit, spun up from a year of daily rain and heat.
 * <p>
 * This is the number that made the Forest Fire Danger Index stop being partly invented. Three of its
 * four inputs are readings; the fourth is a running total that no API publishes, because it is not a
 * measurement of anything — it is the integral of a year of evaporation minus a year of rain, and the
 * only way to have it is to compute it.
 * <p>
 * <strong>Cached far more coarsely than the weather.</strong> A weather anchor covers 30 km for 30
 * minutes. A drought cell covers fifty kilometres for a whole day, because the quantity moves that
 * slowly and that smoothly, and because deriving one costs a year of daily data rather than a single
 * lookup. Persisted for the same reason: losing it on a deploy would make it the most expensive number
 * in the system.
 * <p>
 * Two upstream calls per cell per day. The reanalysis archive covers the long tail and lags real time
 * by a few days; the forecast endpoint's past-days window closes that gap. They are joined by date, so
 * the overlap corrects itself rather than double-counting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DroughtService {

    /**
     * The provider whose free allowance these calls are charged against. Same operator, same tier.
     */
    static final String BUDGET = "open-meteo";

    private final OpenMeteoDailyClient client;
    private final WeatherRepositories.DroughtCellRepository cells;
    private final WeatherBudget budget;
    private final HostLimiter hostLimiter;
    private final ElevationService elevation;
    private final WeatherProperties properties;
    private final WeatherGovernor governor;
    private final Json json;
    private final Map<UUID, Cell> live = new ConcurrentHashMap<>();

    private static List<Double> tail(double[] values, int n) {
        List<Double> out = new ArrayList<>();
        for (int i = Math.max(0, values.length - n); i < values.length; i++) {
            out.add(values[i]);
        }
        return out;
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i) == null ? 0 : values.get(i);
        }
        return out;
    }

    private static String host(String endpoint) {
        return URI.create(endpoint).getHost();
    }

    /**
     * The deficit for a point, spinning one up if no cell nearby is current for today.
     *
     * @param zone the local zone at the point, because a soil moisture deficit is indexed by local
     *             calendar day and Adelaide's day is not UTC's
     */
    public Optional<DroughtIndex> at(double lat, double lon, ZoneId zone) {
        WeatherProperties.Drought cfg = properties.drought();
        if (!cfg.enabled()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(zone);
        Optional<Cell> nearby = nearest(lat, lon, today, governor.tuning().droughtCellRadiusMetres());
        if (nearby.isPresent()) {
            return Optional.of(nearby.get().index());
        }
        return spinUp(lat, lon, today, cfg);
    }

    /**
     * The deficit for a point <em>only if a cell already covers it</em>, never spinning one up.
     * <p>
     * For readers that must not cost anything. The map layer refreshes on a timer with nobody having
     * asked about a particular point, and a lookup that quietly spun up a year of daily data every
     * time somebody panned the map would make the display the most expensive caller in the system.
     */
    public Optional<DroughtIndex> cached(double lat, double lon, ZoneId zone) {
        WeatherProperties.Drought cfg = properties.drought();
        if (!cfg.enabled()) {
            return Optional.empty();
        }
        return nearest(lat, lon, LocalDate.now(zone), governor.tuning().droughtCellRadiusMetres()).map(Cell::index);
    }

    private Optional<Cell> nearest(double lat, double lon, LocalDate today, double radiusMetres) {
        return live.values().stream()
                .filter(c -> c.index().computedFor().equals(today))
                .filter(c -> Geo.planarMetres(lat, lon, c.lat(), c.lon()) <= radiusMetres)
                .min(Comparator.comparingDouble(c -> Geo.planarMetres(lat, lon, c.lat(), c.lon())));
    }

    /**
     * A year of daily rain and maximum temperature, integrated into a deficit and then into a drought
     * factor. Runs at most once per cell per day; a failure returns empty and the caller falls back to
     * the configured factor with the reason attached.
     */
    private Optional<DroughtIndex> spinUp(double lat, double lon, LocalDate today, WeatherProperties.Drought cfg) {
        WeatherBudget.Decision decision = budget.check(BUDGET, cfg.callWeight() + 1);
        if (!decision.allowed()) {
            log.debug("drought spin-up at {},{} skipped: {}", lat, lon, decision.reason()); // the budget said why, once, when it decided
            return Optional.empty();
        }
        LocalDate archiveEnd = today.minusDays(cfg.archiveLagDays());
        LocalDate start = today.minusDays(cfg.spinUpDays());
        long started = System.nanoTime();
        // The one-unit call first, on the host that runs out first. The forecast host shares its
        // allowance with every anchor lookup and is the one the address's daily limit closes; the
        // archive host kept answering while it was shut. Asked the other way round, a refused day
        // fetched a year of history at six units and threw it away, ten times over on 12 September
        // 2026. The step's own weight is what a failure is charged, not the archive's.
        double charged = 1.0;
        try {
            hostLimiter.acquire(host(WeatherProperties.Drought.RECENT_ENDPOINT));
            List<OpenMeteoDailyClient.DailyRow> recent = client.recent(WeatherProperties.Drought.RECENT_ENDPOINT, lat, lon, cfg.archiveLagDays() + 2);
            budget.record(BUDGET, 1.0, true, elapsed(started), "drought recent window");

            started = System.nanoTime();
            charged = cfg.callWeight();
            hostLimiter.acquire(host(WeatherProperties.Drought.ARCHIVE_ENDPOINT));
            List<OpenMeteoDailyClient.DailyRow> archive = client.archive(WeatherProperties.Drought.ARCHIVE_ENDPOINT, lat, lon, start, archiveEnd);
            budget.record(BUDGET, cfg.callWeight(), true, elapsed(started), "drought archive " + start + " to " + archiveEnd);

            // Joined by date so the deliberate overlap between the two windows corrects rather than doubles.
            TreeMap<LocalDate, OpenMeteoDailyClient.DailyRow> byDate = new TreeMap<>();
            archive.forEach(r -> byDate.put(r.date(), r));
            recent.forEach(r -> byDate.put(r.date(), r));
            byDate.headMap(start).clear();
            byDate.tailMap(today.plusDays(1)).clear();
            if (byDate.size() < Kbdi.WINDOW_DAYS) {
                log.warn("drought spin-up at {},{} returned only {} days", lat, lon, byDate.size());
                return Optional.empty();
            }

            List<LocalDate> dates = new ArrayList<>(byDate.keySet());
            double[] rain = new double[dates.size()];
            double[] maxTemp = new double[dates.size()];
            double totalRain = 0;
            for (int i = 0; i < dates.size(); i++) {
                OpenMeteoDailyClient.DailyRow row = byDate.get(dates.get(i));
                rain[i] = row.rainMm() == null ? 0 : row.rainMm();
                maxTemp[i] = row.maxTemperatureC() == null ? Double.NaN : row.maxTemperatureC();
                totalRain += rain[i];
            }
            // Derived from the window itself rather than a climate atlas, and annualised when it is short.
            double annualRainfall = dates.size() >= 300
                    ? totalRain * 365.0 / dates.size()
                    : cfg.defaultAnnualRainfallMm();

            double[] series = Kbdi.series(rain, maxTemp, annualRainfall, 0.0);
            double kbdi = series[series.length - 1];
            List<Double> window = tail(rain, Kbdi.WINDOW_DAYS);
            double factor = Kbdi.droughtFactor(kbdi, toArray(window));
            boolean complete = dates.size() >= 300 && window.size() >= Kbdi.WINDOW_DAYS;

            DroughtIndex index = new DroughtIndex(round1(kbdi), Kbdi.band(kbdi), round1(factor), round1(annualRainfall),
                    dates.getFirst(), today, dates.size(), window, complete,
                    complete
                            ? "integrated from " + dates.size() + " days of daily rain and maximum temperature"
                            : "integrated from only " + dates.size() + " days; the starting deficit still shows through");
            Cell cell = store(lat, lon, index);
            log.info("drought cell at {},{}: KBDI {} mm ({}), drought factor {}, from {} days",
                    round1(lat), round1(lon), index.kbdiMm(), index.kbdiBand(), index.droughtFactor(), dates.size());
            return Optional.of(cell.index());
        } catch (Exception e) {
            budget.record(BUDGET, charged, false, elapsed(started), e.getMessage());
            log.warn("drought spin-up at {},{} failed: {}", lat, lon, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Samples the terrain height and then writes the row. Split the same way {@code WeatherCache.store} is,
     * and for the same reason: the tile fetch must not happen while a database connection is held.
     */
    Cell store(double lat, double lon, DroughtIndex index) {
        OptionalDouble height = elevation.at(lat, lon);
        return persist(lat, lon, height.isPresent() ? height.getAsDouble() : null, index);
    }

    @Transactional
    Cell persist(double lat, double lon, Double terrainM, DroughtIndex index) {
        DroughtCellEntity row = new DroughtCellEntity();
        row.setId(UUID.randomUUID());
        row.setLat(lat);
        row.setLon(lon);
        row.setGeom(Geo.point(lat, lon));
        row.setKbdiMm(index.kbdiMm());
        row.setKbdiBand(index.kbdiBand());
        row.setDroughtFactor(index.droughtFactor());
        row.setMeanAnnualRainfallMm(index.meanAnnualRainfallMm());
        row.setSpunUpFrom(index.spunUpFrom());
        row.setComputedFor(index.computedFor());
        row.setSpinUpDays(index.spinUpDays());
        row.setComplete(index.complete());
        row.setRainHistory(json.write(index.recentRainMm()));
        row.setBasis(index.basis());
        row.setTerrainM(terrainM);
        row.setUpdatedAt(Instant.now());
        cells.save(row);
        Cell cell = new Cell(row.getId(), lat, lon, terrainM, index);
        live.put(cell.id(), cell);
        return cell;
    }

    /**
     * Phase 2: yesterday's cells are useless but today's are not, and re-deriving one is expensive.
     */
    public void rehydrate() {
        live.clear();
        LocalDate from = LocalDate.now(properties.zoneId()).minusDays(1);
        for (DroughtCellEntity row : cells.findByComputedForGreaterThanEqual(from)) {
            List<Double> history = readHistory(row.getRainHistory());
            live.put(row.getId(), new Cell(row.getId(), row.getLat(), row.getLon(), row.getTerrainM(),
                    new DroughtIndex(row.getKbdiMm(), row.getKbdiBand(), row.getDroughtFactor(),
                            row.getMeanAnnualRainfallMm(), row.getSpunUpFrom(), row.getComputedFor(),
                            row.getSpinUpDays(), history, row.isComplete(), row.getBasis())));
        }
        log.info("drought cells rehydrated: {}", live.size());
    }

    @Transactional
    public int sweep() {
        LocalDate keepFrom = LocalDate.now(properties.zoneId()).minusDays(1);
        List<UUID> gone = live.values().stream()
                .filter(c -> c.index().computedFor().isBefore(keepFrom))
                .map(Cell::id)
                .toList();
        gone.forEach(live::remove);
        cells.deleteByComputedForBefore(keepFrom.minusDays(6));
        return gone.size();
    }

    public List<Cell> cells() {
        return live.values().stream().sorted(Comparator.comparing(c -> c.index().computedFor())).toList();
    }

    private List<Double> readHistory(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            Double[] values = json.read(jsonArray, Double[].class);
            return List.of(values);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private Duration elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    /**
     * @param terrainM true ground height under the cell, or null when terrain is off or the tile is missing
     */
    public record Cell(UUID id, double lat, double lon, Double terrainM, DroughtIndex index) {
    }
}
