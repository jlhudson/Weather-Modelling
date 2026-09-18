package au.gully.hexagons;

import au.gully.upstreams.OpenMeteo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static au.gully.science.Numbers.round2;

/**
 * GloFAS river discharge for one river cell — a 5 km cell of its own, because a river is a line and
 * discharge belongs to a particular watercourse — with the past window that makes the number mean
 * something. Fetched once a day per cell.
 *
 * @param cellId   the 5 km cell's id
 * @param hasRiver false when GloFAS models no river here, which is an answer rather than a failure
 * @param series   the past 92 days and the days ahead
 */
public record RiverState(String cellId, double lat, double lon, LocalDate computedFor, boolean hasRiver,
                         List<OpenMeteo.DischargeRow> series) {

    private static final double TREND_BAND = 0.2;

    public RiverState {
        series = series == null ? List.of() : List.copyOf(series);
    }

    /**
     * The three numbers worth reading: now, the baseline, and where it is going, plus the days ahead.
     */
    public River river(LocalDate today) {
        Double current = null;
        double baselineTotal = 0;
        int baselineCount = 0;
        Double peakAhead = null;
        List<OpenMeteo.DischargeRow> ahead = new ArrayList<>();
        for (OpenMeteo.DischargeRow row : series) {
            if (row.cumecs() == null) {
                continue;
            }
            if (row.date().isBefore(today)) {
                baselineTotal += row.cumecs();
                baselineCount++;
                continue;
            }
            ahead.add(row);
            if (row.date().equals(today)) {
                current = row.cumecs();
            } else if (peakAhead == null || row.cumecs() > peakAhead) {
                peakAhead = row.cumecs();
            }
        }
        Double mean = baselineCount == 0 ? null : baselineTotal / baselineCount;
        Double ratio = current == null || mean == null || mean == 0 ? null : round2(current / mean);
        String trend = current == null || peakAhead == null ? null
                : peakAhead > current * (1 + TREND_BAND) ? "RISING" : peakAhead < current * (1 - TREND_BAND) ? "FALLING" : "STEADY";
        return new River(current == null ? null : round2(current), mean == null ? null : round2(mean), ratio, trend, ahead);
    }

    /**
     * @param ratioToMean discharge against its own mean over the past 92 days; the number that means something
     */
    public record River(Double currentCumecs, Double meanCumecs, Double ratioToMean, String trend,
                        List<OpenMeteo.DischargeRow> ahead) {
    }
}
