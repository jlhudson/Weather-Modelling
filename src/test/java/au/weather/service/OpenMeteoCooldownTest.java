package au.weather.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Open-Meteo's refusal names the window that ran out, and the cooldown is that window. The message
 * arrives inside the fetcher's exception text, after the status line and the URL, exactly as the
 * ledger stored it on 12 September 2026.
 */
class OpenMeteoCooldownTest {

    private static final Duration OTHERWISE = Duration.ofMinutes(5);
    private static final String PREFIX = "HTTP 429 from https://api.open-meteo.com/v1/forecast?latitude=-34.8550&longitude=138.5057: ";

    @Test
    void dailyLimitSleepsUntilTheNextUtcMidnight() {
        Instant now = Instant.parse("2026-09-12T08:59:46Z");
        Duration cooldown = OpenMeteoProvider.cooldownFor(
                PREFIX + "{\"error\":true,\"reason\":\"Daily API request limit exceeded. Please try again tomorrow.\"}",
                now, OTHERWISE);
        assertThat(now.plus(cooldown)).isEqualTo(Instant.parse("2026-09-13T00:01:00Z"));
    }

    @Test
    void hourlyLimitSleepsUntilTheTopOfTheNextHour() {
        Instant now = Instant.parse("2026-09-12T08:59:46Z");
        Duration cooldown = OpenMeteoProvider.cooldownFor(
                PREFIX + "{\"error\":true,\"reason\":\"Hourly API request limit exceeded. Please try again in the next hour.\"}",
                now, OTHERWISE);
        assertThat(now.plus(cooldown)).isEqualTo(Instant.parse("2026-09-12T09:01:00Z"));
    }

    @Test
    void minutelyLimitSleepsAMinute() {
        Duration cooldown = OpenMeteoProvider.cooldownFor(
                PREFIX + "{\"error\":true,\"reason\":\"Minutely API request limit exceeded. Please try again in one minute.\"}",
                Instant.parse("2026-09-12T08:59:46Z"), OTHERWISE);
        assertThat(cooldown).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void anyOtherFailureTakesTheSpecsNumber() {
        assertThat(OpenMeteoProvider.cooldownFor("HTTP 500 from https://api.open-meteo.com/v1/forecast", Instant.now(), OTHERWISE))
                .isEqualTo(OTHERWISE);
        assertThat(OpenMeteoProvider.cooldownFor("unreachable: https://api.open-meteo.com (connect timed out)", Instant.now(), OTHERWISE))
                .isEqualTo(OTHERWISE);
        assertThat(OpenMeteoProvider.cooldownFor(null, Instant.now(), OTHERWISE)).isEqualTo(OTHERWISE);
    }

    @Test
    void aBareStatusLineWithNoReasonIsNotReadAsADailyLimit() {
        // The fetcher before 12 September 2026 threw the status line alone; that text must keep the old behaviour.
        assertThat(OpenMeteoProvider.cooldownFor("HTTP 429 from https://api.open-meteo.com/v1/forecast?daily=precipitation_sum",
                Instant.now(), OTHERWISE)).isEqualTo(OTHERWISE);
    }
}
