package au.gully.hexagons;

import au.gully.bureau.Warning;
import au.gully.cfs.Ratings;
import au.gully.science.CsiroGrassland;
import au.gully.science.FireOutlook;
import au.gully.science.WindChange;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The full fire picture on an active hexagon (docs/06 item 16), computed from what the hexagon holds
 * and replaced whenever any of it changes: the McArthur forest and grassland indices, the AFDRS
 * grassland index and its rating, the official district rating and total fire ban, the wind and its
 * next change, the drought behind it all, the warnings covering it, and which index leads. None of
 * it is worked out on request.
 *
 * @param at            the time the "now" values describe
 * @param leads         {@code forest}, {@code grass}, or null where the land use is unknown or unburnable
 * @param appliesToPct  how much of the hexagon the indices describe: its burnable share, or null
 *                      when the land use is unknown
 * @param grass         the grassland indices, or null where no curing figure is held for the district
 * @param official      the CFS rating for the district, or null outside South Australia
 * @param fireWeatherWarning whether a Bureau fire weather warning covers the hexagon right now
 */
public record FirePicture(
        Instant at,
        Double ffdi,
        String ffdiRating,
        Double peakFfdi,
        Double droughtFactor,
        Double kbdiMm,
        String kbdiBand,
        String leads,
        Integer appliesToPct,
        Grass grass,
        Official official,
        Wind wind,
        boolean fireWeatherWarning,
        List<Warning> warnings,
        List<FireOutlook> outlook,
        Double vapourPressureDeficitKpa,
        Double soilMoistureSurface,
        Double soilMoistureRootZone,
        Double boundaryLayerHeightM,
        Double windSpeed80mKmh,
        Integer windDirection80mDeg,
        Double capeJkg,
        Double liftedIndex
) {

    public FirePicture {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        outlook = outlook == null ? List.of() : List.copyOf(outlook);
    }

    /**
     * The grassland indices: McArthur's meter and the AFDRS model on the same inputs.
     *
     * @param curingPct       the district's figure, and when it was entered
     * @param fuelLoadTHa     the load the indices were run at: McArthur's standard 4.5 t/ha
     * @param condition       the grass condition the AFDRS model was run for
     * @param rateOfSpreadKmh the AFDRS model's headline figure
     * @param fbi             the AFDRS Fire Behaviour Index, floored as published
     * @param afdrsRating     the word for it
     */
    public record Grass(Integer curingPct, LocalDate curingEnteredOn, Double fuelLoadTHa, String condition,
                        Double gfdi, String gfdiRating, Double spreadKmh, Double moisturePct,
                        Double rateOfSpreadKmh, Long intensityKwm, Double flameHeightM, Integer fbi, String afdrsRating) {

        public static Grass of(Integer curingPct, LocalDate enteredOn, double fuelLoadTHa, Double gfdi,
                               CsiroGrassland.Result csiro) {
            return new Grass(curingPct, enteredOn, fuelLoadTHa,
                    csiro == null ? null : csiro.condition().name().toLowerCase().replace('_', '-'),
                    gfdi, gfdi == null ? null : au.gully.science.GrassFireDanger.rating(gfdi),
                    gfdi == null ? null : au.gully.science.Numbers.round2(au.gully.science.GrassFireDanger.spreadKmh(gfdi)),
                    csiro == null ? null : csiro.moisturePct(),
                    csiro == null ? null : csiro.rateOfSpreadKmh(),
                    csiro == null ? null : csiro.intensityKwm(),
                    csiro == null ? null : csiro.flameHeightM(),
                    csiro == null ? null : csiro.fbi(),
                    csiro == null ? null : csiro.rating());
        }
    }

    /**
     * The official rating for the fire ban district, as published, for today and the days ahead.
     */
    public record Official(String district, String rating, Integer fbi, boolean totalFireBan, LocalDate date,
                           Instant from, Instant to, List<Ratings.RatingDay> days, Instant readAt) {

        public Official {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    /**
     * The wind now and its next change.
     */
    public record Wind(Double speedKmh, Integer directionDeg, Double gustKmh, String band, WindChange change) {
    }
}
