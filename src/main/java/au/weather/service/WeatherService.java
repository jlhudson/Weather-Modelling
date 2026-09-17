package au.weather.service;

import au.weather.core.*;
import au.weather.http.HostLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static au.weather.core.Numbers.round1;

/**
 * The front door: a latitude and a longitude in, the weather out, and as few upstream calls as the
 * configuration will allow.
 * <p>
 * The order of operations is the whole design and it is deliberately boring:
 * <ol>
 *   <li><strong>the cache first</strong>, on both axes - near enough and recent enough answers without
 *       any call at all, which on a fire ground with twenty appliances is the difference between one
 *       call and twenty;</li>
 *   <li><strong>then the providers in configured order</strong>, skipping any that is unconfigured, out
 *       of allowance, or cooling down after a failure. The free ones come first and the billed one last;</li>
 *   <li><strong>then a stale reading</strong> if there is one, clearly labelled with its age, because a
 *       ninety-minute-old temperature that says it is ninety minutes old is worth more than nothing and
 *       far less dangerous than a ninety-minute-old temperature that does not (docs/13).</li>
 * </ol>
 * On top of the report it assembles the two derived blocks. <strong>Fire</strong> is the index now and
 * across the forecast, resting on a soil moisture deficit that is integrated rather than assumed.
 * <strong>Flood</strong> is what has already fallen, what is still coming, how full the ground is, and
 * what the river is doing. Both are cached on their own, much coarser, clocks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final List<WeatherProvider> providers;
    private final WeatherCache cache;
    private final WeatherBudget budget;
    private final DroughtService drought;
    private final FloodService flood;
    private final HostLimiter hostLimiter;
    private final WeatherProperties properties;
    private final WeatherGovernor governor;

    private static Integer maxRainProbability(WeatherReport report) {
        Integer max = null;
        for (Conditions c : report.hourly()) {
            if (c.precipitationProbabilityPct() != null && (max == null || c.precipitationProbabilityPct() > max)) {
                max = c.precipitationProbabilityPct();
            }
        }
        for (DayOutlook d : report.daily()) {
            if (d.precipitationProbabilityPct() != null && (max == null || d.precipitationProbabilityPct() > max)) {
                max = d.precipitationProbabilityPct();
            }
        }
        return max;
    }

    private static Double add(Double a, Double b) {
        if (a == null && b == null) {
            return null;
        }
        return round1((a == null ? 0 : a) + (b == null ? 0 : b));
    }

    /**
     * How the answer got here, in one line an operator can act on. The height difference is named rather
     * than folded into the reach, because "8 km away" and "8 km away and 300 m below" are different facts
     * and only one of them explains why the temperature looks wrong.
     */
    private static String describe(WeatherCache.Hit hit, WeatherCache.Selection found) {
        long minutes = hit.age().toMinutes();
        String where = String.format("anchor %.1f km away", hit.distanceMetres() / 1000.0);
        if (hit.elevationDeltaMetres() == null) {
            return String.format("%s (%s), %d min old", where, found.note(), minutes);
        }
        double delta = hit.elevationDeltaMetres();
        // The sign reads from the query point's side: "below" means the anchor sits below where we asked.
        String vertical = Math.abs(delta) < 1 ? "level with it"
                : String.format("%.0f m %s", Math.abs(delta), delta > 0 ? "below" : "above");
        return String.format("%s, %s, reach %.1f km, %d min old",
                where, vertical, hit.reachMetres() / 1000.0, minutes);
    }

    /**
     * The provider registered under this id, or null. Five beans; a scan is cheaper than a map.
     */
    private WeatherProvider provider(String id) {
        return providers.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- fire

    /**
     * The weather at a point, from the cache where possible. Empty only when nothing at all can answer.
     */
    public Optional<WeatherAnswer> at(double lat, double lon) {
        return at(lat, lon, false);
    }

    /**
     * @param force skip the cache and call an upstream. For the operator button on the console and for
     *              nothing else: every other caller should be content with a reading from nearby.
     */
    public Optional<WeatherAnswer> at(double lat, double lon, boolean force) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        // One walk of the cache, both tiers. Which one is used depends on whether an upstream answers,
        // which is this method's decision rather than the cache's.
        WeatherCache.Selection found = cache.find(lat, lon);

        if (!force && found.green().isPresent()) {
            WeatherCache.Hit hit = found.green().get();
            cache.serveGreen(hit);
            return Optional.of(answer(lat, lon, hit, true, "cache: " + describe(hit, found)));
        }

        List<String> skipped = new ArrayList<>();
        for (String id : properties.order()) {
            WeatherProvider provider = provider(id);
            if (provider == null) {
                skipped.add(id + ": no such provider");
                continue;
            }
            if (!provider.configured()) {
                skipped.add(id + ": " + provider.unavailableReason());
                continue;
            }
            WeatherBudget.Decision decision = budget.check(id);
            if (!decision.allowed()) {
                skipped.add(id + ": " + decision.reason());
                continue;
            }
            long started = System.nanoTime();
            try {
                hostLimiter.acquire(provider.host());
                WeatherReport report = provider.fetch(lat, lon, governor.tuning().span());
                Duration latency = Duration.ofNanos(System.nanoTime() - started);
                budget.record(id, true, latency, null);
                cache.missed();
                WeatherCache.Anchor anchor = cache.store(lat, lon, report);
                String note = skipped.isEmpty() ? "fetched from " + id
                        : "fetched from " + id + " after skipping " + String.join("; ", skipped);
                log.debug("weather at {},{}: {}", lat, lon, note);
                return Optional.of(answer(lat, lon,
                        new WeatherCache.Hit(anchor, 0, 0, null, Duration.ZERO, WeatherCache.Tier.GREEN), false, note));
            } catch (Exception e) {
                Duration latency = Duration.ofNanos(System.nanoTime() - started);
                budget.record(id, false, latency, e.getMessage());
                log.warn("weather provider {} failed at {},{}: {}", id, lat, lon, e.toString());
                skipped.add(id + ": " + e.getMessage());
            }
        }

        // Everything upstream is out of allowance, unconfigured or broken. An old reading, honestly aged,
        // and from however far away it had to come: at this point the alternative is no weather at all.
        if (found.yellow().isPresent()) {
            WeatherCache.Hit hit = found.yellow().get();
            cache.serveYellow(hit);
            return Optional.of(answer(lat, lon, hit, true,
                    "stale: " + describe(hit, found) + "; no provider available (" + String.join("; ", skipped) + ")"));
        }
        cache.missed();
        // Per point at DEBUG: on a day every provider is out this is every anchor every tick, and the
        // manager says once per tick how many went without. The provider that failed said why when it did.
        log.debug("no weather available at {},{}: {}", lat, lon, String.join("; ", skipped));
        return Optional.empty();
    }

    // ---------------------------------------------------------------- flood

    private WeatherAnswer answer(double lat, double lon, WeatherCache.Hit hit, boolean cached, String decision) {
        WeatherReport report = hit.anchor().report();
        ZoneId zone = zoneOf(report);
        DroughtIndex index = properties.fire().enabled() ? drought.at(lat, lon, zone).orElse(null) : null;
        return new WeatherAnswer(report, fireWeather(report, index),
                floodWeather(report, index, flood.at(lat, lon, zone).orElse(null)),
                index, cached, round1(hit.distanceMetres()), round1(hit.reachMetres()),
                hit.elevationDeltaMetres() == null ? null : round1(hit.elevationDeltaMetres()),
                hit.age(), hit.anchor().id().toString(), decision);
    }

    /**
     * The index now, the index across the forecast, and the deficit both rest on.
     * <p>
     * Skipped entirely when temperature, humidity or wind is missing rather than defaulted: an FFDI
     * built on a substituted humidity is a confident number about nothing. The drought factor is the
     * one input that may still be assumed, and when it is, {@code estimated} says so.
     */
    private FireWeather fireWeather(WeatherReport report, DroughtIndex index) {
        WeatherProperties.Fire fire = properties.fire();
        if (!fire.enabled()) {
            return null;
        }
        Conditions c = report.current();
        double factor = index == null ? fire.fallbackDroughtFactor() : index.droughtFactor();
        boolean estimated = index == null || !index.complete();
        String basis = index == null ? fire.basis() : index.basis();

        Double ffdi = FireDanger.of(c, factor);
        List<FireOutlook> outlook = outlook(report, index, factor);

        return new FireWeather(ffdi, ffdi == null ? null : FireDanger.rating(ffdi), round1(factor),
                index == null ? null : index.kbdiMm(), index == null ? null : index.kbdiBand(),
                index == null ? null : index.meanAnnualRainfallMm(),
                c == null ? null : c.vapourPressureDeficitKpa(),
                c == null ? null : c.soilMoistureSurface(),
                c == null ? null : c.soilMoistureRootZone(),
                c == null ? null : c.boundaryLayerHeightM(),
                c == null ? null : c.windSpeed80mKmh(),
                c == null ? null : c.windDirection80mDeg(),
                c == null ? null : c.capeJkg(),
                c == null ? null : c.liftedIndex(),
                outlook, estimated, basis);
    }

    /**
     * The forecast index day by day. With a spun-up deficit the projection carries it forward through
     * each day's rain and heat; without one the assumed factor is held flat, which is worse but is at
     * least visibly worse, because the whole block is then marked estimated.
     */
    private List<FireOutlook> outlook(WeatherReport report, DroughtIndex index, double fallbackFactor) {
        List<FireDanger.FireDay> days = new ArrayList<>();
        for (DayOutlook d : report.daily()) {
            days.add(new FireDanger.FireDay(d.date(), d.maxTemperatureC(), d.minHumidityPct(),
                    d.maxWindKmh(), d.maxGustKmh(), d.precipitationMm()));
        }
        if (days.isEmpty()) {
            return List.of();
        }
        if (index != null) {
            return FireDanger.outlook(days, index.kbdiMm(), index.meanAnnualRainfallMm(), index.recentRainMm());
        }
        List<FireOutlook> out = new ArrayList<>();
        for (FireDanger.FireDay day : days) {
            Double value = day.maxTemperatureC() == null || day.minHumidityPct() == null || day.maxWindKmh() == null
                    ? null
                    : round1(FireDanger.ffdi(day.maxTemperatureC(), day.minHumidityPct(), day.maxWindKmh(), fallbackFactor));
            out.add(new FireOutlook(day.date(), value, value == null ? null : FireDanger.rating(value),
                    null, round1(fallbackFactor), day.maxTemperatureC(), day.minHumidityPct(),
                    day.maxWindKmh(), day.maxGustKmh(), day.rainMm()));
        }
        return out;
    }

    /**
     * What has fallen, what is coming, how full the ground is, and what the river is doing.
     * <p>
     * The antecedent half comes free: the drought spin-up already fetched a year of daily rain, and this
     * reads the last week of it. One expensive fetch answers two questions that look unrelated - the
     * fire block asks how long since it rained, this asks how much has fallen.
     */
    private FloodWeather floodWeather(WeatherReport report, DroughtIndex index, FloodService.River river) {
        if (!properties.flood().enabled()) {
            return null;
        }
        Conditions current = report.current();
        List<DayOutlook> daily = report.daily();
        Double next24 = rainOverNextHours(report, 24);
        Double next48 = add(next24, daily.size() > 1 ? daily.get(1).precipitationMm() : null);
        Double next72 = add(next48, daily.size() > 2 ? daily.get(2).precipitationMm() : null);

        Map<LocalDate, Double> dischargeByDate = new LinkedHashMap<>();
        if (river != null) {
            river.ahead().forEach(r -> dischargeByDate.put(r.date(), r.cumecs()));
        }

        List<FloodOutlook> outlook = new ArrayList<>();
        for (DayOutlook d : daily) {
            Double discharge = dischargeByDate.get(d.date());
            Double ratio = discharge == null || river == null || river.meanCumecs() == null || river.meanCumecs() == 0
                    ? null : round1(discharge / river.meanCumecs());
            outlook.add(new FloodOutlook(d.date(), d.precipitationMm(), d.precipitationProbabilityPct(), discharge, ratio));
        }

        List<String> notes = new ArrayList<>();
        notes.add(index == null ? "no rainfall history: antecedent totals unavailable"
                : "antecedent rainfall from the " + index.spinUpDays() + "-day drought spin-up");
        notes.add(river == null ? "no modelled river within 5 km, or discharge unavailable"
                : "river discharge from GloFAS; the baseline is the mean of the past 92 days, not a climatology");

        return new FloodWeather(
                index == null ? null : index.rainOverLast(1),
                index == null ? null : index.rainOverLast(2),
                index == null ? null : index.rainOverLast(3),
                index == null ? null : index.rainOverLast(7),
                rainOverNextHours(report, 6), rainOverNextHours(report, 12), next24, next48, next72,
                maxRainProbability(report),
                current == null ? null : current.soilMoistureSurface(),
                current == null ? null : current.soilMoistureRootZone(),
                river == null ? null : river.currentCumecs(),
                river == null ? null : river.meanCumecs(),
                river == null ? null : river.ratioToMean(),
                river == null ? null : river.trend(),
                outlook, String.join("; ", notes));
    }

    /**
     * Rain still to come, from the hourly series forward of now. Null when the series carries none.
     */
    private Double rainOverNextHours(WeatherReport report, int hours) {
        Instant now = Instant.now();
        Instant until = now.plus(Duration.ofHours(hours));
        double total = 0;
        boolean any = false;
        for (Conditions c : report.hourly()) {
            if (c.at() == null || c.at().isBefore(now) || c.at().isAfter(until)) {
                continue;
            }
            if (c.precipitationMm() != null) {
                total += c.precipitationMm();
                any = true;
            }
        }
        return any ? round1(total) : null;
    }

    /**
     * The zone a report keeps its days in: the provider's, else the configured one. Public because the
     * forecast structure needs it to put an hour on its day, and one resolution with one fallback is
     * better than three callers each deciding what a missing zone means.
     */
    public ZoneId zoneOf(WeatherReport report) {
        try {
            return report.zoneId() == null ? properties.zoneId() : ZoneId.of(report.zoneId());
        } catch (RuntimeException e) {
            return properties.zoneId();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Phase 2, before any event flows: the cache, the ledger, the drought cells and the rivers all survive.
     */
    public void rehydrate() {
        cache.rehydrate();
        budget.rehydrate();
        drought.rehydrate();
        flood.rehydrate();
    }

    /**
     * Called on a timer: drop what nothing can use, and trim the ledger.
     */
    public int sweep() {
        budget.prune();
        drought.sweep();
        flood.sweep();
        return cache.sweep();
    }

    /**
     * The full fire block for an anchor, built only from what is already cached.
     * <p>
     * Exists so the map layer stops re-deriving a thinner version of it. The layer used to compute the
     * index itself from three fields, which meant the map and {@code /api/weather} were two implementations
     * of the same arithmetic drifting apart - exactly what {@code WeatherJson} was written to prevent. This
     * is the same call the lookup path makes, so the index, its rating and the whole forecast outlook are
     * identical wherever they are read.
     * <p>
     * <strong>Cannot spend allowance.</strong> Uses {@link DroughtService#cached}, which never spins a cell
     * up, because the caller is a layer on a sixty-second refresh timer with nobody having asked about a
     * particular point. Returns null where no drought cell reaches, rather than falling back to the
     * configured drought factor: on a map an index resting on an assumed number would be indistinguishable
     * from one resting on a real one, and the gap in coverage is the thing worth seeing.
     */
    public FireWeather cachedFireAt(double lat, double lon, WeatherReport report) {
        if (!properties.fire().enabled()) {
            return null;
        }
        DroughtIndex index = drought.cached(lat, lon, zoneOf(report)).orElse(null);
        return index == null ? null : fireWeather(report, index);
    }

    /**
     * The flood block from cached inputs only, for the same caller and the same reason as
     * {@link #cachedFireAt}.
     * <p>
     * Both halves degrade rather than vanish, and differently. With no drought cell the antecedent
     * totals are null but the forecast rain is still real, because it comes off the anchor's own daily
     * series; with no river cell the discharge is null but everything else stands. A map that showed
     * nothing unless both were present would hide the rain, which is the half that is always there.
     */
    public FloodWeather cachedFloodAt(double lat, double lon, WeatherReport report) {
        if (!properties.flood().enabled()) {
            return null;
        }
        ZoneId zone = zoneOf(report);
        return floodWeather(report, drought.cached(lat, lon, zone).orElse(null),
                flood.cached(lat, lon, zone).orElse(null));
    }

    public WeatherGovernor governor() {
        return governor;
    }

    /**
     * Steps the governor if its interval has elapsed. Called from the manager tick, on the writer thread.
     */
    public boolean govern(java.time.Instant at) {
        return governor.maybeRecompute(at);
    }

    public WeatherCache cache() {
        return cache;
    }

    public DroughtService drought() {
        return drought;
    }

    public FloodService flood() {
        return flood;
    }

    public WeatherProperties properties() {
        return properties;
    }

    /**
     * Everything the console needs about every provider, in configured order.
     */
    public List<WeatherStatus> status() {
        List<WeatherStatus> out = new ArrayList<>();
        for (String id : properties.order()) {
            WeatherProvider provider = provider(id);
            WeatherBudget.Decision decision = budget.check(id);
            WeatherBudget.Failure failure = budget.lastFailure(id);
            WeatherProvider.Spec spec = provider == null ? null : provider.spec();
            out.add(new WeatherStatus(id,
                    provider == null ? null : provider.host(),
                    spec == null ? null : spec.model(),
                    provider != null && provider.configured(),
                    provider == null ? "no such provider" : provider.unavailableReason(),
                    decision.allowed(),
                    decision.reason(),
                    spec != null && spec.commercialSafe(),
                    spec == null ? 0 : spec.callWeight(),
                    spec == null ? 0 : spec.guardFraction(),
                    spec == null ? WeatherProvider.Limits.NONE : spec.limits(),
                    budget.spending(id),
                    spec == null ? null : spec.attribution(),
                    failure == null ? null : failure.reason(),
                    failure == null ? null : failure.at(),
                    failure == null ? null : failure.until()));
        }
        return out;
    }

}
