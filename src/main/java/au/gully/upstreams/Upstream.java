package au.gully.upstreams;

import au.gully.hexagons.Cell;
import au.gully.platform.UpstreamException;

import java.time.Duration;
import java.util.List;

/**
 * One upstream that can answer "what is the weather at this hexagon's centre". The smallest possible
 * interface: a cell in, a normalised {@link Forecast} out, and no opinion about budgets, breakers or
 * the order of fallback — those are {@link Upstreams}, and putting them here would mean writing
 * them once per upstream.
 * <p>
 * <strong>Every fact about an upstream lives on the class that talks to it</strong>: endpoint,
 * model, licence, published allowance and what one fetch costs against it, in its {@link Spec}.
 * None of that is configuration, because none of it is a deployment choice. What an operator decides
 * — which upstreams are on, and in what order — is {@code gully.upstreams.order}.
 */
public interface Upstream {

    Spec spec();

    default String id() {
        return spec().id();
    }

    /**
     * Able to run: an endpoint, and a key present where one is required.
     */
    default boolean configured() {
        return unavailableReason() == null;
    }

    /**
     * Why it cannot be called, for the console; null when it can.
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * How long to leave this upstream alone after a failure that said this about itself. The spec's
     * pause is the answer for a failure that says nothing; an upstream whose refusal names the window
     * that ran out overrides it, because a minute's limit and a day's want pauses three orders of
     * magnitude apart.
     */
    default Duration pauseAfter(String failureDetail, int status) {
        return spec().pauseAfterFailure();
    }

    /**
     * One forecast for one cell. Throws rather than returning a partial answer.
     */
    Forecast fetch(Cell cell) throws UpstreamException;

    /**
     * Everything fixed about one upstream, declared beside the code that calls it.
     *
     * @param unitsPerFetch     what one {@link #fetch} costs against the allowance, in the upstream's
     *                          own units — Open-Meteo counts variables and span, Google counts calls
     * @param limits            the published free allowance, as researched rather than as hoped
     * @param guard             the fraction of a limit at which the upstream is retired, so the free
     *                          tier is never actually exhausted
     * @param perMinute         the real per-minute limit the pacer holds calls to
     * @param bills             whether use past the free tier is charged — the overflow, not a peer
     * @param pauseAfterFailure how long the breaker stays open when a failure says nothing about itself
     */
    record Spec(String id, String host, String model, String attribution, double unitsPerFetch, Limits limits,
                double guard, int perMinute, boolean bills, Duration pauseAfterFailure, List<String> variables) {

        public Spec {
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }

    /**
     * A published allowance. A null field means the upstream publishes no limit on that window.
     */
    record Limits(Integer perMinute, Integer perHour, Integer perDay, Integer perMonth) {
    }
}
