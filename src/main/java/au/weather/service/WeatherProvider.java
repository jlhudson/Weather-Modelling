package au.weather.service;

import au.weather.http.SourceException;
import au.weather.core.WeatherReport;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * One upstream that can answer "what is the weather at this point". Deliberately the smallest possible
 * interface: a point in, a normalised report out, and no opinion about caching, budgets or fallback -
 * all three of those are the service, and putting them here would mean writing them once per provider.
 * <p>
 * A provider never rate-limits itself either. It declares its {@link #host()} and the shared
 * {@code HostLimiter} does it, in the same bucket the scheduled sources draw from (docs/03 3.1).
 * <p>
 * <strong>Every fact about an upstream lives on the class that talks to it</strong> — its endpoint, its
 * model name, its licence, its published free allowance and what one report costs against it, all in
 * the {@link Spec} constant at the top of the implementation. None of it is configuration, because
 * none of it is a deployment choice: it is what the upstream published, and it changes when the
 * upstream changes, which is a code change. What an operator actually decides —
 * <em>which</em> providers to use and in what order — is {@code weather.order}.
 */
public interface WeatherProvider {

    /**
     * What this upstream is, what it costs and what it is allowed to cost.
     */
    Spec spec();

    /**
     * The key this provider is budgeted under, e.g. {@code open-meteo}.
     */
    default String id() {
        return spec().id();
    }

    /**
     * The host its calls land on, for the shared per-host budget.
     */
    default String host() {
        return spec().host();
    }

    /**
     * Able to run: an endpoint, and a key present where one is required.
     */
    default boolean configured() {
        return unavailableReason().isEmpty();
    }

    /**
     * Why it cannot be called, for the console. Empty when it can.
     */
    default String unavailableReason() {
        return "";
    }

    /**
     * How long to leave this upstream alone after it failed in the way described.
     * <p>
     * The spec's one number is the answer for a failure that says nothing about itself. A provider whose
     * refusal names the window that ran out overrides this, because the right cooldown for a minute's
     * limit and a day's limit differ by three orders of magnitude, and retrying a day's limit every five
     * minutes is two hundred and eighty-eight refused round trips that each cost what a success would.
     */
    default Duration cooldownAfter(String failureDetail) {
        return spec().cooldownAfterFailure();
    }

    /**
     * One report for one point, over the span the caller is willing to pay for. Throws rather than returning a partial report.
     */
    WeatherReport fetch(double lat, double lon, Span span) throws SourceException;

    /**
     * Everything fixed about one upstream, declared next to the code that calls it.
     */
    record Spec(String id, String endpoint, String model, String attribution, boolean commercialSafe,
                double callWeight, double guardFraction, List<String> extras, Limits limits,
                Duration cooldownAfterFailure) {

        /**
         * The published allowances only; everything else takes the researched defaults below.
         */
        public static Spec of(String id, String endpoint, String model, String attribution,
                              boolean commercialSafe, double callWeight, Limits limits) {
            return new Spec(id, endpoint, model, attribution, commercialSafe, callWeight, 0.9,
                    List.of(), limits, Duration.ofMinutes(5));
        }

        public Spec withExtras(List<String> extras) {
            return new Spec(id, endpoint, model, attribution, commercialSafe, callWeight, guardFraction,
                    List.copyOf(extras), limits, cooldownAfterFailure);
        }

        public Spec withCooldown(Duration cooldown) {
            return new Spec(id, endpoint, model, attribution, commercialSafe, callWeight, guardFraction,
                    extras, limits, cooldown);
        }

        public Spec withEndpoint(String endpoint) {
            return new Spec(id, endpoint, model, attribution, commercialSafe, callWeight, guardFraction,
                    extras, limits, cooldownAfterFailure);
        }

        public String host() {
            return URI.create(endpoint).getHost();
        }
    }

    /**
     * A published free allowance, as researched rather than as hoped. A null field means the provider
     * publishes no limit on that window.
     */
    record Limits(Integer perMinute, Integer perHour, Integer perDay, Integer perMonth) {

        public static final Limits NONE = new Limits(null, null, null, null);

        public static Limits perMinute(int n) {
            return new Limits(n, null, null, null);
        }

        public static Limits perMonth(int n) {
            return new Limits(null, null, null, n);
        }
    }

    /**
     * How far ahead this call may ask.
     * <p>
     * Passed in rather than read from configuration by each provider, because the span is a budget
     * decision and this interface already says budgets belong to the service. Open-Meteo weights a call by
     * variables multiplied by span, so a week of forecast genuinely costs more than three days of it, and
     * the component that knows what is left to spend is the one that should choose.
     */
    record Span(int days, int hours) {
    }
}
