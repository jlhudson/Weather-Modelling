package au.gully.hexagons;

import au.gully.upstreams.Forecast;
import au.gully.upstreams.Upstreams;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a forecast is kept (docs/06 item 0, W-7 as rewritten): a hard cap counted from the fetch,
 * {@link #FORECAST} in the ordinary way and {@link #FORECAST_WHEN_TIGHT} once the upstream's day has
 * used {@link #TIGHT_AT} of its allowance — a longer life is fewer fetches, which is the one lever
 * that matters when the allowance is running out. Inside that life a station in the hexagon can
 * still throw the forecast out early ({@link Drift}); nothing keeps one past it.
 * <p>
 * The cap is read at the moment it is asked about, not fixed when the forecast was fetched, so a
 * budget that tightens at three in the afternoon stretches every forecast already held.
 */
@Component
public class Life {

    /**
     * How long a forecast is kept, from its fetch.
     */
    public static final Duration FORECAST = Duration.ofHours(3);

    /**
     * How long a forecast is kept once the allowance is tight.
     */
    public static final Duration FORECAST_WHEN_TIGHT = Duration.ofHours(5);

    /**
     * The share of the day's allowance spent at which the budget counts as tight. The budget itself
     * stops fetching at 90 per cent; this stretches the life well before that.
     */
    public static final double TIGHT_AT = 0.7;

    private final Upstreams upstreams;

    public Life(Upstreams upstreams) {
        this.upstreams = upstreams;
    }

    /**
     * Whether the day's allowance is tight now.
     */
    public boolean tight() {
        return upstreams.dayFraction() >= TIGHT_AT;
    }

    /**
     * The life in force now.
     */
    public Duration forecast() {
        return tight() ? FORECAST_WHEN_TIGHT : FORECAST;
    }

    /**
     * When a forecast's life ends, under the cap in force now.
     */
    public Instant expiresAt(Forecast f) {
        return f == null || f.fetchedAt() == null ? null : f.fetchedAt().plus(forecast());
    }

    public boolean expired(Forecast f, Instant now) {
        Instant at = expiresAt(f);
        return at == null || !now.isBefore(at);
    }
}
