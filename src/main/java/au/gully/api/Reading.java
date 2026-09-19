package au.gully.api;

import au.gully.science.Conditions;
import au.gully.science.DroughtIndex;
import au.gully.science.FloodWeather;
import au.gully.science.WindChange;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The reading: what {@code GET /api/v1/readings} answers, and the contract The Hub reads by name
 * ({@code contract/reading.schema.json} is the same shape written down, and the test that the two
 * agree is {@code ReadingContractTest}). Typed, so the OpenAPI document is generated from it and a
 * renamed field is a compile error here and a failed build there.
 * <p>
 * There is no {@code generatedAt}: the body is the reading, so its fingerprint only changes when the
 * reading does, and a conditional GET works. No confidence scores, no "estimate" or "actual"
 * branching: a value is present or it is null, and the values are the values.
 *
 * @param available   whether there is a reading at all; false with {@code unavailable} saying why
 * @param at          the time {@code current} describes: the station's observation time, or the model's
 * @param currentFrom {@code station} when the hexagon's Bureau station supplied "now" - in it, or within a
 *                    quarter of its width of its edge - {@code stations} when several were blended,
 *                    {@code neighbours} when the stations around it were (W-13), {@code model} otherwise
 * @param station     the nearest Bureau station's latest values, inside the hexagon or not, with its distance
 * @param nearby      how the stations' values were blended and brought here, when they were (ring 0 is
 *                    the hexagon's own stations); null otherwise
 * @param drift       the station in the hexagon against the forecast it holds (W-12); null without both
 * @param history     present when the reading is a snapshot answered for a past time
 */
public record Reading(
        String schema,
        boolean available,
        String unavailable,
        Point point,
        HexagonBlock hexagon,
        Source source,
        Instant at,
        Conditions current,
        String currentFrom,
        StationBlock station,
        NearbyBlock nearby,
        FireBlock fire,
        FloodWeather flood,
        DroughtIndex drought,
        List<WarningBlock> warnings,
        ForecastBlock forecast,
        DriftBlock drift,
        HistoryBlock history,
        String disclaimer
) {

    public static final String SCHEMA = "gully/reading/1";

    public static final String DISCLAIMER = "Weather from third-party forecast models and the Bureau of Meteorology's "
            + "published station values, held per 17 km hexagon; the fire indices are computed here and the official "
            + "rating is the CFS's. Not an official Bureau of Meteorology product.";

    public record Point(double lat, double lon) {
    }

    /**
     * @param kind       {@code station}, {@code forecast}, {@code both} or {@code bare}
     * @param refreshedAt when the forecast was fetched; null without one
     * @param expiresAt  when the forecast's life ends under the cap in force now; null without a forecast
     */
    public record HexagonBlock(String id, double lat, double lon, double widthKm, Double elevationM,
                               String elevationFrom, Double slopeDeg, String zone, String fireBanDistrict,
                               String bureauDistrict, LandUseBlock landUse, String stationId, String kind,
                               Instant activatedAt, Instant refreshedAt, Instant expiresAt) {
    }

    /**
     * @param point   the class at the point asked about, read off the hexagon's raster
     * @param percent share of the hexagon per class, whole numbers, absent classes absent
     * @param leads   {@code forest} or {@code grass}: which index describes this ground
     * @param source  the raster: {@code dea-landcover-2025} (Digital Earth Australia, that calendar year) or a mounted file's name
     */
    public record LandUseBlock(String point, Map<String, Integer> percent, String leads, Integer burnablePct, String source) {
    }

    /**
     * Which upstream the forecast came from, and how long it is kept.
     *
     * @param expiresAt when its life ends under the cap in force now
     * @param life      the cap in force now, ISO-8601: {@code PT3H}, or {@code PT5H} while the allowance is tight
     * @param stale     true when its life has ended and nothing has answered since
     */
    public record Source(String upstream, String model, String attribution, Instant fetchedAt, Instant expiresAt,
                         String life, boolean stale) {
    }

    /**
     * The station in the hexagon against the forecast, at the station's time: each of the four as
     * station minus forecast, the worst as a share of its tolerance, and whether that threw the
     * forecast out.
     */
    public record DriftBlock(Instant at, String stationId, String upstream, Double temperatureC, Integer humidityPct,
                             Double windKmh, Double rainMm, double score, String worst, boolean drifted) {
    }

    public record StationBlock(String id, String name, double lat, double lon, Double heightM, Double distanceKm,
                               boolean insideHexagon, Instant at, Double temperatureC, Double apparentTemperatureC,
                               Double dewPointC, Integer humidityPct, Double windSpeedKmh, Integer windDirectionDeg,
                               String windDirection, Double windGustKmh, Double pressureMslHpa, Double rainSince9amMm,
                               Double rain24hMm, Double maxTemperatureC, Double minTemperatureC, Double visibilityKm,
                               String cloud) {
    }

    /**
     * The neighbouring stations that made "now" (W-13): how far out the search went, each station with
     * its distance, height and share of the answer, and the elevation the values were brought to.
     */
    public record NearbyBlock(int ring, List<NearbyStation> stations, Double elevationM, boolean elevationApplied,
                              double lapseTemperatureCPerKm, double lapseDewPointCPerKm) {
    }

    public record NearbyStation(String id, String name, double distanceKm, Double heightM, double weight) {
    }

    /**
     * The fire picture (docs/06 item 16).
     */
    public record FireBlock(Double ffdi, String ffdiRating, Double peakFfdi, Double droughtFactor, Double kbdiMm,
                            String kbdiBand, String leads, Integer appliesToPct, GrassBlock grass, OfficialBlock official,
                            WindBlock wind, boolean fireWeatherWarning, Double vapourPressureDeficitKpa,
                            Double soilMoistureSurface, Double soilMoistureRootZone, Double boundaryLayerHeightM,
                            Double windSpeed80mKmh, Integer windDirection80mDeg, Double capeJkg, Double liftedIndex) {
    }

    public record GrassBlock(Integer curingPct, LocalDate curingEnteredOn, Double fuelLoadTHa, String condition,
                             Double gfdi, String gfdiRating, Double spreadKmh, Double moisturePct, Double rateOfSpreadKmh,
                             Long intensityKwm, Double flameHeightM, Integer fbi, String afdrsRating) {
    }

    public record OfficialBlock(String district, String rating, Integer fbi, boolean totalFireBan, LocalDate date,
                                Instant from, Instant to, List<OfficialDay> days, Instant readAt) {
    }

    public record OfficialDay(int day, LocalDate date, String rating, Integer fbi, boolean totalFireBan, Instant from, Instant to) {
    }

    public record WindBlock(Double speedKmh, Integer directionDeg, Double gustKmh, String band, WindChange change) {
    }

    public record WarningBlock(String id, String title, String phenomena, String headline, String hazard, String severity,
                               Instant issuedAt, Instant from, Instant until, String link) {
    }

    /**
     * The days ahead and the hours ahead, each day carrying its own fire and flood so nothing joins
     * three lists on a date string, and the first wind change in the next two days.
     */
    public record ForecastBlock(List<Day> days, List<Hour> hours, WindChange windChange) {
    }

    public record Day(LocalDate date, Double maxTemperatureC, Double minTemperatureC, Double maxApparentTemperatureC,
                      Integer minHumidityPct, Double maxWindKmh, Double maxGustKmh, Integer windDirectionDeg,
                      Double precipitationMm, Integer precipitationProbabilityPct, Double uvIndexMax, Instant sunrise,
                      Instant sunset, String condition, DayFire fire, DayFlood flood) {
    }

    public record DayFire(Double ffdi, String ffdiRating, Double gfdi, String gfdiRating, Integer fbi, String afdrsRating,
                          Double kbdiMm, Double droughtFactor, Double maxTemperatureC, Integer minHumidityPct,
                          Double maxWindKmh, Double maxGustKmh, Double rainMm) {
    }

    public record DayFlood(Double rainMm, Integer rainProbabilityPct, Double riverDischargeCumecs, Double dischargeRatioToMean) {
    }

    /**
     * One forecast hour: the eight fields anyone reads across a row, plus the indices for the hour.
     */
    public record Hour(Instant at, Double temperatureC, Integer humidityPct, Double windSpeedKmh, Integer windDirectionDeg,
                       Double windGustKmh, Double precipitationMm, Integer precipitationProbabilityPct, String condition,
                       HourFire fire) {
    }

    public record HourFire(Double ffdi, String ffdiRating, Double gfdi, String gfdiRating, Integer fbi, String afdrsRating,
                           Double droughtFactor) {
    }

    /**
     * @param at       the snapshot's own time, which may be hours from the time asked for
     * @param askedAt  when the snapshot was taken
     * @param ref      the reference the ask carried: what the reading was for
     */
    public record HistoryBlock(Instant at, Instant askedAt, String ref, Instant requested) {
    }
}
