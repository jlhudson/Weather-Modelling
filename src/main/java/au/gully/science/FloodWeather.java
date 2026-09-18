package au.gully.science;

import java.util.List;

import static au.gully.science.Numbers.round1;

/**
 * Flood weather at a point: what has already fallen, what is still coming, how full the ground is,
 * and what the river is doing.
 * <p>
 * <strong>Antecedent rain is the half people forget.</strong> Fifty millimetres onto dry ground is a
 * wet afternoon; fifty onto ground that took eighty over the previous three days is a callout. The
 * same daily history that feeds the drought index is read the other way round here — the drought
 * index asks how long since it rained, this asks how much has fallen recently.
 * <p>
 * The antecedent totals are <strong>calendar days</strong>, not rolling hours: they come from the
 * daily series behind the drought index, so {@code rain1dMm} is today so far rather than the last
 * twenty-four hours.
 *
 * @param riverDischargeCumecs modelled discharge of the largest river within about 5 km, from GloFAS
 * @param dischargeRatioToMean discharge against that river's own mean over the past 92 days; the
 *                             ratio travels because the raw figure is meaningless without the river
 */
public record FloodWeather(
        Double rain1dMm,
        Double rain2dMm,
        Double rain3dMm,
        Double rain7dMm,
        Double forecastRain6hMm,
        Double forecastRain12hMm,
        Double forecastRain24hMm,
        Double forecastRain48hMm,
        Double forecastRain72hMm,
        Integer maxRainProbabilityPct,
        Double soilMoistureSurface,
        Double soilMoistureRootZone,
        Double riverDischargeCumecs,
        Double riverDischargeMeanCumecs,
        Double dischargeRatioToMean,
        String riverTrend,
        List<FloodOutlook> outlook
) {

    public FloodWeather {
        outlook = outlook == null ? List.of() : List.copyOf(outlook);
    }

    /**
     * Rain already down plus rain still coming over the next three days, which is the planning figure.
     */
    public Double threeDayTotalMm() {
        if (rain3dMm == null && forecastRain72hMm == null) {
            return null;
        }
        double past = rain3dMm == null ? 0 : rain3dMm;
        double coming = forecastRain72hMm == null ? 0 : forecastRain72hMm;
        return round1(past + coming);
    }
}
