package au.gully.fire;

import au.gully.upstreams.Conditions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OutlookTest {

    static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");
    static final LocalTime NINE = LocalTime.of(9, 0);
    // 2 pm in Adelaide on 25 September 2026 (ACST, +9:30): the Bureau day of the 25th is running.
    static final Instant NOW = Instant.parse("2026-09-25T04:30:00Z");

    /** Seventy-two hours from the hour now running, each made by a function of its index. */
    static List<Conditions> series(IntFunction<Conditions.Builder> hour) {
        List<Conditions> out = new ArrayList<>();
        for (int i = 0; i < 72; i++) {
            out.add(hour.apply(i).build());
        }
        return out;
    }

    static Instant at(int i) {
        return Instant.parse("2026-09-25T04:30:00Z").plusSeconds(3600L * i);
    }

    @Test
    void anHourIsItsOwnValuesWithTheDaysDroughtFactor() {
        double[] dry = new double[20];
        List<Conditions> flat = series(i -> Conditions.at(at(i)).temperature(30.0).humidity(20).wind(30.0).precipitation(0.0));
        List<Outlook.Hour> hours = Outlook.hours(flat, 100, 500, dry, NOW, ADELAIDE, NINE);
        assertThat(hours).hasSize(72);
        double df = Kbdi.droughtFactor(100, dry);
        // The hour now running, on today's factor: McArthur's meter by hand, Noble, Bary and Gill (1980).
        double byHand = 2.0 * Math.exp(-0.450 + 0.987 * Math.log(df) - 0.0345 * 20 + 0.0338 * 30 + 0.0234 * 30);
        assertThat(hours.getFirst().ffdi()).isCloseTo(byHand, within(0.05));
        assertThat(hours.getFirst().droughtFactor()).isCloseTo(df, within(0.05));
        // Tomorrow's hours take the deficit stepped through today's heat: drier, never wetter, on a dry day.
        Outlook.Hour tomorrow = hours.stream().filter(h -> h.at().equals(Instant.parse("2026-09-26T00:30:00Z"))).findFirst().orElseThrow();
        assertThat(tomorrow.kbdiMm()).isEqualTo(Math.round(Kbdi.step(100, 30, 0, 500) * 10) / 10.0);
        assertThat(tomorrow.kbdiMm()).isGreaterThan(100);
    }

    @Test
    void theDaysFigureIsItsWorstHourNotItsExtremesTogether() {
        // The hottest hour is humid and still; the windiest is cool. Putting the day's maximum temperature, least
        // humidity and strongest wind together would be an hour that never happened.
        List<Conditions> day = series(i -> switch (i) {
            case 0 -> Conditions.at(at(i)).temperature(38.0).humidity(60).wind(5.0).precipitation(0.0);
            case 3 -> Conditions.at(at(i)).temperature(20.0).humidity(15).wind(60.0).precipitation(0.0);
            default -> Conditions.at(at(i)).temperature(25.0).humidity(40).wind(15.0).precipitation(0.0);
        });
        double[] dry = new double[20];
        List<Outlook.Hour> hours = Outlook.hours(day, 120, 500, dry, NOW, ADELAIDE, NINE);
        Outlook.Day today = Outlook.days(hours, day, ADELAIDE).getFirst();
        assertThat(today.date()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(today.peakAt()).isEqualTo(at(3));
        assertThat(today.temperatureC()).isEqualTo(20.0);
        assertThat(today.windSpeedKmh()).isEqualTo(60.0);
        double together = FireDanger.ffdi(38, 15, 60, today.droughtFactor());
        assertThat(today.ffdiMax()).isLessThan(together);
    }

    @Test
    void aWetDayAheadLowersTheDayAfter() {
        double[] dry = new double[20];
        // Twenty-five millimetres in the hour beginning 3 am on the 26th - inside the Bureau day of the 25th.
        List<Conditions> wet = series(i -> Conditions.at(at(i)).temperature(28.0).humidity(25).wind(25.0).precipitation(i == 13 ? 25.0 : 0.0));
        List<Conditions> dryRun = series(i -> Conditions.at(at(i)).temperature(28.0).humidity(25).wind(25.0).precipitation(0.0));
        Instant tomorrowNoon = Instant.parse("2026-09-26T02:30:00Z");
        Outlook.Hour afterRain = Outlook.hours(wet, 120, 500, dry, NOW, ADELAIDE, NINE).stream().filter(h -> h.at().equals(tomorrowNoon)).findFirst().orElseThrow();
        Outlook.Hour noRain = Outlook.hours(dryRun, 120, 500, dry, NOW, ADELAIDE, NINE).stream().filter(h -> h.at().equals(tomorrowNoon)).findFirst().orElseThrow();
        assertThat(afterRain.kbdiMm()).isLessThan(noRain.kbdiMm());
        assertThat(afterRain.droughtFactor()).isLessThan(noRain.droughtFactor());
        assertThat(afterRain.ffdi()).isLessThan(noRain.ffdi());
        // The hours still inside the Bureau day of the 25th keep today's factor: the rain has not been counted yet.
        Outlook.Hour beforeNine = Outlook.hours(wet, 120, 500, dry, NOW, ADELAIDE, NINE).stream().filter(h -> h.at().equals(Instant.parse("2026-09-25T22:30:00Z"))).findFirst().orElseThrow();
        assertThat(beforeNine.droughtFactor()).isEqualTo(Math.round(Kbdi.droughtFactor(120, dry) * 10) / 10.0);
    }
}
