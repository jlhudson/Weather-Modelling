package au.gully.record;

import au.gully.fire.Kbdi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * A station's drought, integrated from its own record (W-6): the Keetch-Byram deficit run day by
 * day from field capacity at the start of the year behind today, the mean annual rainfall the run
 * needs taken from that same year, and the Griffiths drought factor from the deficit and the last
 * twenty days of rain, today's so far included. Nothing is stored: the record is, and this is
 * arithmetic over it, done when asked.
 *
 * @param from             the first day integrated
 * @param to               the last complete day, yesterday
 * @param days             how many days the record held in the year
 * @param complete         whether the year is nearly whole - the deficit has had its spin-up
 * @param meanAnnualRainMm the year's rain scaled to a year, which sets how fast this ground dries
 * @param kbdiMm           the deficit at the end of yesterday, 0 to 203.2
 * @param droughtFactor    0 to 10, what the fire indices consume
 * @param recentRainMm     the twenty days of rain the factor was drawn from, oldest first, today last
 * @param computedFor      the day the values are for
 */
public record Drought(LocalDate from, LocalDate to, int days, boolean complete, double meanAnnualRainMm, double kbdiMm,
                      double droughtFactor, String band, double[] recentRainMm, LocalDate computedFor) {

    /**
     * Fewer days than this and there is no drought to speak of: the factor's own window.
     */
    public static final int MIN_DAYS = Kbdi.WINDOW_DAYS;
    /**
     * A year with a fortnight of gaps is still a year.
     */
    public static final int COMPLETE_DAYS = Record.SPIN_UP_DAYS - 14;

    /**
     * The drought for a day, from a record, or empty when the record is too short to say.
     *
     * @param today      the Bureau day now running
     * @param rainSoFar  today's rain since 9 am, or null when nothing has said
     */
    public static Optional<Drought> of(NavigableMap<LocalDate, Record.Day> record, LocalDate today, Double rainSoFar) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate start = today.minusDays(Record.SPIN_UP_DAYS);
        NavigableMap<LocalDate, Record.Day> year = record.subMap(start, true, yesterday, true);
        List<Record.Day> withRain = year.values().stream().filter(d -> d.rainMm() != null).toList();
        if (withRain.size() < MIN_DAYS) {
            return Optional.empty();
        }
        LocalDate first = withRain.getFirst().day(), last = withRain.getLast().day();
        long span = java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
        double rainSum = withRain.stream().mapToDouble(Record.Day::rainMm).sum();
        double mar = Math.round(rainSum * 365.0 / span * 10) / 10.0;
        // The series over the days held, gaps skipped: a missing day neither rains nor dries.
        double[] rain = new double[withRain.size()], max = new double[withRain.size()];
        for (int i = 0; i < withRain.size(); i++) {
            rain[i] = withRain.get(i).rainMm();
            max[i] = withRain.get(i).maxTempC() == null ? Double.NaN : withRain.get(i).maxTempC();
        }
        double[] series = Kbdi.series(rain, max, mar, 0);
        double kbdi = Math.round(series[series.length - 1] * 10) / 10.0;
        // The last twenty days by the calendar, a day the record lacks counted as dry, today so far last.
        List<Double> recent = new ArrayList<>();
        for (LocalDate d = today.minusDays(Kbdi.WINDOW_DAYS - 1); !d.isAfter(yesterday); d = d.plusDays(1)) {
            Record.Day day = record.get(d);
            recent.add(day == null || day.rainMm() == null ? 0.0 : day.rainMm());
        }
        recent.add(rainSoFar == null ? 0.0 : rainSoFar);
        double[] recentRain = recent.stream().mapToDouble(Double::doubleValue).toArray();
        double df = Math.round(Kbdi.droughtFactor(kbdi, recentRain) * 10) / 10.0;
        return Optional.of(new Drought(first, last, withRain.size(), withRain.size() >= COMPLETE_DAYS, mar, kbdi, df, Kbdi.band(kbdi), recentRain, today));
    }
}
