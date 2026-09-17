package au.weather.core;

import java.util.List;

/**
 * Fire weather at a point: the Forest Fire Danger Index now, the same index across the forecast, and
 * the soil moisture deficit both rest on.
 * <p>
 * A metric, not a fire model (docs/09 9.3): roughly how dangerous the atmosphere is, never a predicted
 * perimeter. What changed is that it is no longer partly invented — the drought factor is now
 * integrated from a year of daily rain and temperature rather than read from configuration, so
 * {@link #estimated} is false whenever the spin-up completed.
 * <p>
 * <strong>Still no grassland index.</strong> GFDI needs curing and fuel load, which are not weather
 * and which nothing here ingests yet; when the fuel manager exists it will combine them with this.
 * Publishing a grassland number derived from an assumed curing would repeat exactly the mistake the
 * drought factor has just stopped making.
 *
 * @param droughtFactor 0-10, from {@link Kbdi}; the fourth FFDI input
 * @param kbdiMm        the soil moisture deficit behind it, millimetres
 * @param outlook       one entry per forecast day
 * @param estimated     true only when the drought factor fell back to configuration
 * @param basis         one line naming what was assumed, if anything, and how deep the spin-up went
 */
public record FireWeather(
        Double ffdi,
        String ffdiRating,
        double droughtFactor,
        Double kbdiMm,
        String kbdiBand,
        Double meanAnnualRainfallMm,
        Double vapourPressureDeficitKpa,
        Double soilMoistureSurface,
        Double soilMoistureRootZone,
        Double boundaryLayerHeightM,
        Double windSpeed80mKmh,
        Integer windDirection80mDeg,
        Double capeJkg,
        Double liftedIndex,
        List<FireOutlook> outlook,
        boolean estimated,
        String basis
) {

    public FireWeather {
        outlook = outlook == null ? List.of() : List.copyOf(outlook);
    }

    /**
     * The worst index across today and the forecast, which is the number a duty officer reads first.
     */
    public Double peakFfdi() {
        Double peak = ffdi;
        for (FireOutlook day : outlook) {
            if (day.ffdi() != null && (peak == null || day.ffdi() > peak)) {
                peak = day.ffdi();
            }
        }
        return peak;
    }
}
