package au.weather.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * What a deployment may change about the <em>application</em>, as against the weather (D-147): who may
 * log into the console, and who may call the API. Everything has its value here rather than in
 * {@code application.yml}, so the shipped yml carries only what genuinely differs between one machine
 * and another. Secrets are never here; they arrive from {@code .env} (D-101).
 *
 * <p>This is the Hub's {@code HubProperties}, minus the two data directories and the polling floor —
 * there are no file-backed sources here and nothing is polled. The Hub's {@code Polling} record went
 * with them.
 *
 * <p><strong>Why the prefix is {@code weather.app} and not {@code weather}.</strong>
 * {@code WeatherProperties} already binds {@code weather}, and two records on one prefix only works in
 * Boot while their key sets do not overlap — which is a property nobody can see from either file and
 * that the next key added to either one quietly breaks. A separate prefix makes the two independent:
 * {@code weather.app.console.code}, {@code weather.app.api.cors-origins}.
 */
@ConfigurationProperties(prefix = "weather.app")
public record WeatherAppProperties(
        @DefaultValue Console console,
        @DefaultValue Api api
) {

    /**
     * One user, an 8-digit code, lockout on consecutive failures (D-123).
     */
    public record Console(@DefaultValue("12345678") String code,
                          @DefaultValue("5") int lockoutAfter,
                          @DefaultValue("15m") Duration lockoutFor) {
    }

    /**
     * @param corsOrigins empty by default: same-origin only until a consumer is actually named
     */
    public record Api(@DefaultValue List<String> corsOrigins,
                      @DefaultValue("600") int requestsPerMinutePerKey) {
    }
}
