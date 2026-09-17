package au.weather.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * The levers an operator pulls on the weather service, and nothing else.
 * <p>
 * Every value has its default here rather than in {@code application.yml}, so the shipped
 * configuration is empty and this record is the single readable answer to "what does it do by default,
 * and what may I change" (D-147). Naming a key in the yml overrides one line of it.
 * <p>
 * What is <em>not</em> here is everything fixed about an upstream - its endpoint, model, licence,
 * published allowance and call cost. That lives on the provider class that talks to it
 * ({@link WeatherProvider.Spec}), because none of it is a deployment choice. The choice that is left is
 * {@link #order}: which upstreams to use, and in what sequence.
 *
 * @param order         provider ids in preference order; the first that is configured, healthy and
 *                      inside its free allowance answers. Google sits last on purpose: it is the one
 *                      that bills. {@code open-meteo-bom} is absent on purpose, because the Bureau has
 *                      open-data delivery suspended - see {@link OpenMeteoProvider#BOM}
 * @param contact       an email or URL identifying this deployment, required in the User-Agent by MET
 *                      Norway and merely polite everywhere else
 * @param zone          the time zone daily aggregates are cut on, for providers that report no zone of
 *                      their own. Open-Meteo resolves the real zone at the point and that one wins
 * @param forecastDays  how far the daily outlook runs. Short on purpose: Open-Meteo weights a call by
 *                      variables multiplied by span, so a fortnight of forecast is several calls
 * @param forecastHours how far the hourly series runs; 72 covers every forecast day, so each one can
 *                      get its own driest hour
 */
@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("15s") Duration timeout,
        @DefaultValue("https://github.com/jlhudson/Weather-Modelling") String contact,
        @DefaultValue("Australia/Adelaide") String zone,
        @DefaultValue("3") int forecastDays,
        @DefaultValue("72") int forecastHours,
        @DefaultValue({"open-meteo", "google"}) List<String> order,
        @DefaultValue Cache cache,
        @DefaultValue Refresh refresh,
        @DefaultValue Fire fire,
        @DefaultValue Drought drought,
        @DefaultValue Flood flood,
        @DefaultValue Governor governor
) {

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /**
     * The two-axis cache: a reading is reusable if it is recent enough <em>and</em> near enough. These
     * two numbers decide how many upstream calls a busy day costs, which is why they are the first
     * thing an operator watching a free allowance reaches for.
     *
     * @param ttl            how long a reading stays reusable. Inside this and inside the radius, a
     *                       request is answered without touching an upstream at all
     * @param maxStale       the oldest reading that may still be served when every provider is refusing
     *                       or out of allowance. A degraded answer that says how old it is beats no
     *                       answer; an answer that does not say beats nothing at all, which is why the
     *                       age travels with it (docs/13 is about stale looking identical to current)
     * @param reachKm        how far a reading may be stretched, measured in three dimensions rather than
     *                       two. Twenty kilometres nominal against a model grid of roughly fifteen, so an
     *                       anchor stands in for about one grid cell instead of four. The governor moves
     *                       it between its floor and its ceiling; this is only where it starts
     * @param verticalWeight metres of horizontal cost per metre of height difference. At 67, three
     *                       hundred metres of climb costs the same as twenty kilometres of travel, which
     *                       separates the Hills from the plains and changes nothing in flat country.
     *                       <strong>Ships at zero</strong>, which reproduces the old horizontal-only rule
     *                       exactly: the reach numbers appear on the console beside the distances they
     *                       replace, and the weight is turned up once those numbers have been looked at
     * @param maxAnchors     ceiling on live anchors, coldest evicted first. Bounds memory by incident
     *                       activity rather than by area (docs/09 9.2)
     * @param sweepInterval  how often expired anchors leave memory and the table
     * @param retainFor      how long an expired anchor row is kept, so a restart sees recent history
     */
    public record Cache(@DefaultValue("30m") Duration ttl,
                        @DefaultValue("3h") Duration maxStale,
                        @DefaultValue("20") double reachKm,
                        @DefaultValue("0") double verticalWeight,
                        @DefaultValue("500") int maxAnchors,
                        @DefaultValue("15m") Duration sweepInterval,
                        @DefaultValue("48h") Duration retainFor) {

        public double reachMetres() {
            return reachKm * 1000.0;
        }
    }

    /**
     * The bands the governor moves the cache between, and how fast it is allowed to move.
     *
     * <p><strong>This is insurance, not a daily tuner, and it should be read that way.</strong> Against
     * Open-Meteo's ten thousand units a day, a busy incident day spends a few hundred, so pressure sits
     * near zero and every value rests at its floor essentially always. The machinery earns its keep on one
     * kind of day: the one where Open-Meteo is down and Google's three hundred guarded calls a day are
     * carrying the load. That is exactly the day the cache should stretch, and exactly the day nobody has
     * time to change a configuration file.
     *
     * <p><strong>So the floor is the operating value.</strong> A fifteen-kilometre reach against a model
     * grid of roughly the same size means an anchor stands in for about one grid cell; that costs roughly
     * four times the calls a thirty-kilometre reach did, and it is affordable only because the allowance is
     * an order of magnitude larger than the appetite for it.
     *
     * @param deadBand      how far the signal must move before anything changes, so the reach does not
     *                      twitch every hour on noise
     * @param steps         notches from floor to ceiling. One notch per recompute, so eight steps means a
     *                      full traverse takes eight hours and nothing lurches
     * @param interval      how often the governor may step. It runs off the existing weather sweep tick, so
     *                      the real cadence is the coarser of this and that
     * @param headroomHolds consecutive intervals of headroom required before stepping <em>toward</em> the
     *                      floor. Asymmetric on purpose: relieving cost should be immediate, spending more
     *                      should have to be earned. One hour of quiet is not evidence of a quiet day
     */
    public record Governor(@DefaultValue("true") boolean enabled,
                           @DefaultValue("15") double reachFloorKm,
                           @DefaultValue("50") double reachCeilingKm,
                           @DefaultValue("25") double droughtFloorKm,
                           @DefaultValue("100") double droughtCeilingKm,
                           @DefaultValue("5") double riverFloorKm,
                           @DefaultValue("15") double riverCeilingKm,
                           @DefaultValue("90m") Duration ttlCeiling,
                           @DefaultValue("7") int forecastDaysCeiling,
                           @DefaultValue("0.15") double deadBand,
                           @DefaultValue("8") int steps,
                           @DefaultValue("1h") Duration interval,
                           @DefaultValue("2") int headroomHolds) {

        /**
         * The time-to-live never goes below this, whatever the configuration says, because a configurable
         * floor is not a floor. Under half an hour the anchor cache stops being a cache and becomes a
         * proxy, and the models behind it only publish a new run every one to six hours anyway - so the
         * calls bought below this line re-fetch the same forecast.
         */
        public static final Duration TTL_FLOOR = Duration.ofMinutes(30);
    }

    /**
     * How often the sweep runs.
     *
     * <p>In the Hub this record also held when an <em>incident</em> weather estimate was worth asking
     * for again (docs/09 9.4): {@code onRaise}, {@code onUpgrade}, {@code openIncidentInterval},
     * {@code moveSigmaMultiple}, {@code maxPerTick} and {@code maxIncidentAge}. Those were read only by
     * {@code WeatherManager}'s incident half, which stayed behind (D-249: this service holds the cache,
     * the Hub holds the question). The record survives for the one key that is still ours, so
     * {@code weather.refresh.tick-interval} means here what {@code hub.weather.refresh.tick-interval}
     * meant there.
     *
     * @param tickInterval how often the sweeper governs, sweeps the cache and backfills terrain
     */
    public record Refresh(@DefaultValue("5m") Duration tickInterval) {
    }

    /**
     * The Forest Fire Danger Index. Three inputs are readings; the fourth is the drought factor below,
     * which is integrated rather than assumed - so the value here is only a fallback for when the
     * spin-up cannot run at all. There is deliberately no grassland index: GFDI needs curing and fuel
     * load, which are not weather. See {@code FireWeather}.
     *
     * @param fallbackDroughtFactor 0-10, used only when {@link Drought} is disabled or its call fails
     * @param basis                 the sentence that travels with an index built on that fallback
     */
    public record Fire(@DefaultValue("true") boolean enabled,
                       @DefaultValue("8") double fallbackDroughtFactor,
                       @DefaultValue("drought factor assumed from configuration: the spin-up did not run")
                       String basis) {
    }

    /**
     * The soil moisture deficit spin-up: a year of daily rain and heat, integrated into a KBDI and then
     * a Griffiths drought factor. The expensive part of the feature, and so cached far more coarsely
     * than anything else - one cell serves 50 km for a whole day, because the quantity moves that slowly.
     *
     * @param spinUpDays              how far back the integration runs. A year is enough for the assumed
     *                                starting deficit to have washed out; much less and the result leans
     *                                on that assumption, which is why the answer reports its own depth
     * @param cellRadiusKm            how far one spun-up cell may be reused. Deliberately far larger than
     *                                the weather anchor radius, because the quantity is far smoother
     * @param archiveLagDays          how far behind real time the reanalysis archive is assumed to run
     * @param callWeight              a year of daily data is several allowance units, not one; Open-Meteo
     *                                weights by variables multiplied by span
     * @param defaultAnnualRainfallMm used only if the window is too short to derive it
     */
    public record Drought(@DefaultValue("true") boolean enabled,
                          @DefaultValue("365") int spinUpDays,
                          @DefaultValue("50") double cellRadiusKm,
                          @DefaultValue("5") int archiveLagDays,
                          @DefaultValue("6") double callWeight,
                          @DefaultValue("550") double defaultAnnualRainfallMm) {

        /**
         * The reanalysis archive, which lags real time by {@code archiveLagDays}.
         */
        public static final String ARCHIVE_ENDPOINT = "https://archive-api.open-meteo.com/v1/archive";

        /**
         * The forecast endpoint with {@code past_days}, which closes that gap and supplies the outlook.
         */
        public static final String RECENT_ENDPOINT = "https://api.open-meteo.com/v1/forecast";

        public double cellRadiusMetres() {
            return cellRadiusKm * 1000.0;
        }
    }

    /**
     * The flood block. Rainfall and ground saturation come free with the drought spin-up and the
     * weather report; only river discharge costs a call of its own.
     *
     * @param cellRadiusKm tight on purpose. Discharge is a property of a particular river, and GloFAS
     *                     answers for the largest river within about 5 km, so a wide reuse radius would
     *                     confidently report the wrong watercourse
     */
    public record Flood(@DefaultValue("true") boolean enabled,
                        @DefaultValue("true") boolean dischargeEnabled,
                        @DefaultValue("5") double cellRadiusKm,
                        @DefaultValue("1") double callWeight,
                        @DefaultValue("7") int forecastDays) {

        /**
         * GloFAS river discharge, the one flood input that is a call rather than a by-product.
         */
        public static final String ENDPOINT = "https://flood-api.open-meteo.com/v1/flood";

        public double cellRadiusMetres() {
            return cellRadiusKm * 1000.0;
        }
    }
}
