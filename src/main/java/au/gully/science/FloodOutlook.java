package au.gully.science;

import java.time.LocalDate;

/**
 * One forecast day of flood weather: how much rain is coming, and what the river is expected to do.
 *
 * @param riverDischargeCumecs modelled discharge of the largest river within about 5 km, m³/s. Null
 *                             away from any modelled river, which is a real answer and not a gap
 * @param dischargeRatioToMean discharge against the same river's mean for the period. This is the
 *                             number worth reading: 400 m³/s means nothing without knowing whether
 *                             that river usually runs at 20 or at 2 000
 */
public record FloodOutlook(
        LocalDate date,
        Double rainMm,
        Integer rainProbabilityPct,
        Double riverDischargeCumecs,
        Double dischargeRatioToMean
) {
}
