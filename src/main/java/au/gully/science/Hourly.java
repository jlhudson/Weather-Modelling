package au.gully.science;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

import static au.gully.science.Numbers.round1;

/**
 * Rolling an hourly series up into days, which is a property of the series rather than of whoever
 * fetched it.
 *
 * <p>Two providers needed this and each had written it out. MET Norway publishes a series and no daily
 * summary at all, so its whole outlook is built here. Open-Meteo publishes both, but not every model
 * carries the daily humidity and probability aggregates - and asking a model for a variable it does
 * not have is a 400, not a null - so those two are taken from the hourly series it already paid for.
 *
 * <p><strong>Nulls are absent, not zero.</strong> Every aggregate ignores missing samples and returns
 * null when a day has none, because "no wind reported" and "no wind" decide different things about a
 * fire danger index.
 */
@UtilityClass
public class Hourly {

    /**
     * The series split by local date, in the order the days arrive.
     */
    public static Map<LocalDate, List<Conditions>> byDate(List<Conditions> hourly, ZoneId zone) {
        Map<LocalDate, List<Conditions>> byDate = new LinkedHashMap<>();
        for (Conditions c : hourly) {
            byDate.computeIfAbsent(c.at().atZone(zone).toLocalDate(), d -> new ArrayList<>()).add(c);
        }
        return byDate;
    }

    /**
     * The series as an outlook, capped at {@code days}. Sunrise and sunset are left null: they are an
     * ephemeris rather than a forecast, and a provider that publishes them fills them in itself.
     */
    public static List<DayOutlook> rollUp(List<Conditions> hourly, ZoneId zone, int days) {
        List<DayOutlook> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Conditions>> e : byDate(hourly, zone).entrySet()) {
            if (out.size() >= days) {
                break;
            }
            List<Conditions> day = e.getValue();
            out.add(new DayOutlook(e.getKey(),
                    max(day, Conditions::temperatureC),
                    min(day, Conditions::temperatureC),
                    max(day, Conditions::apparentTemperatureC),
                    minInt(day, Conditions::humidityPct),
                    max(day, Conditions::windSpeedKmh),
                    max(day, Conditions::windGustKmh),
                    firstOf(day, Conditions::windDirectionDeg),
                    totalRain(day),
                    maxInt(day, Conditions::precipitationProbabilityPct),
                    max(day, Conditions::uvIndex),
                    null, null,
                    firstOf(day, Conditions::condition)));
        }
        return out;
    }

    /**
     * The driest hour of one day. The dampest hour is not the number a fire danger index wants: FFDI
     * is computed at the worst of the day, so the minimum is the honest input.
     */
    public static Integer minHumidityOn(List<Conditions> hourly, LocalDate date, ZoneId zone) {
        return minInt(on(hourly, date, zone), Conditions::humidityPct);
    }

    /**
     * The best chance of rain on one day, which is the one worth reporting against a fire ground.
     */
    public static Integer maxProbabilityOn(List<Conditions> hourly, LocalDate date, ZoneId zone) {
        return maxInt(on(hourly, date, zone), Conditions::precipitationProbabilityPct);
    }

    private static List<Conditions> on(List<Conditions> hourly, LocalDate date, ZoneId zone) {
        return hourly.stream().filter(c -> c.at().atZone(zone).toLocalDate().equals(date)).toList();
    }

    private static Double max(List<Conditions> day, Function<Conditions, Double> f) {
        return day.stream().map(f).filter(Objects::nonNull).max(Double::compareTo).orElse(null);
    }

    private static Double min(List<Conditions> day, Function<Conditions, Double> f) {
        return day.stream().map(f).filter(Objects::nonNull).min(Double::compareTo).orElse(null);
    }

    private static Integer minInt(List<Conditions> day, Function<Conditions, Integer> f) {
        return day.stream().map(f).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
    }

    private static Integer maxInt(List<Conditions> day, Function<Conditions, Integer> f) {
        return day.stream().map(f).filter(Objects::nonNull).max(Integer::compareTo).orElse(null);
    }

    private static <T> T firstOf(List<Conditions> day, Function<Conditions, T> f) {
        return day.stream().map(f).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Rain accumulates, so it sums rather than peaks - but a day with no reading at all stays null.
     */
    private static Double totalRain(List<Conditions> day) {
        double total = 0;
        boolean any = false;
        for (Conditions c : day) {
            if (c.precipitationMm() != null) {
                total += c.precipitationMm();
                any = true;
            }
        }
        return any ? round1(total) : null;
    }
}
