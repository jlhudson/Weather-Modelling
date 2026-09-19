package au.gully.science;

import java.time.Instant;

/**
 * The weather at one point at one moment, in one shape whatever produced it (docs/09 9.1).
 * <p>
 * Every field is nullable and every field is SI-with-Australian-habits: Celsius, km/h, millimetres,
 * hPa, per cent, metres. A provider that does not carry a variable leaves it null rather than
 * substituting a zero, because a zero wind speed and an unknown wind speed are different facts and
 * only one of them may be used to compute a fire danger index.
 * <p>
 * It is a wide record and deliberately so. The variables below the general block are the ones fire and
 * flood turn on, and they are here rather than in a separate fire-shaped type because they are not
 * fire-shaped: soil moisture decides infiltration for a flood and fuel dryness for a fire, and
 * boundary layer height disperses bushfire smoke and a hazmat plume identically. Splitting them by the
 * caller that happens to want them would file the same measurement in two places.
 *
 * @param at                       the instant these conditions describe, not the instant they were fetched
 * @param humidityPct              relative humidity at 2 m; with temperature and wind, three of the four FFDI inputs
 * @param windSpeedKmh             10 m mean wind, the FFDI convention
 * @param windGustKmh              10 m gust; what actually moves a fire front and what rolls a truck
 * @param vapourPressureDeficitKpa how hard the air is pulling moisture out of fuel. Rises with heat and
 *                                 falls with humidity, and tracks fuel drying better than either alone
 * @param soilMoistureSurface      volumetric water content 0-1 cm, m³/m³. Fine fuel dryness, and the
 *                                 layer that decides whether rain soaks in or runs off
 * @param soilMoistureRootZone     27-81 cm. Slow, and near saturation it is why a catchment responds
 *                                 to rain it would have absorbed a week earlier
 * @param boundaryLayerHeightM     the depth air mixes through. Low means smoke and plume stay down
 * @param liftedIndex              atmospheric instability; negative is unstable. With CAPE, the pair
 *                                 that says whether a fire can build its own weather
 * @param windSpeed80mKmh          wind above the surface layer, which is what aircraft work in
 * @param condition                the provider's own words for the sky, kept verbatim and never parsed
 */
public record Conditions(
        Instant at,
        // ---- general
        Double temperatureC,
        Double apparentTemperatureC,
        Double dewPointC,
        Integer humidityPct,
        Double windSpeedKmh,
        Integer windDirectionDeg,
        Double windGustKmh,
        Double precipitationMm,
        Integer precipitationProbabilityPct,
        Double pressureMslHpa,
        Integer cloudCoverPct,
        Double visibilityM,
        Double uvIndex,
        Boolean daytime,
        String condition,
        // ---- fire
        Double vapourPressureDeficitKpa,
        Double evapotranspirationMm,
        Double soilMoistureSurface,
        Double soilMoistureShallow,
        Double soilMoistureRootZone,
        Double soilTemperatureC,
        Double boundaryLayerHeightM,
        Double capeJkg,
        Double liftedIndex,
        Double convectiveInhibitionJkg,
        Double windSpeed80mKmh,
        Integer windDirection80mDeg,
        Double shortwaveRadiationWm2,
        // ---- flood
        Double showersMm,
        Double snowfallCm
) {

    public static Builder at(Instant at) {
        return new Builder(at);
    }

    /**
     * Whether the three inputs a fire danger index cannot be honest without are all present.
     */
    public boolean fireInputsPresent() {
        return temperatureC != null && humidityPct != null && windSpeedKmh != null;
    }

    /**
     * Mutable while a provider fills it in from a payload whose fields arrive in no useful order.
     */
    public static final class Builder {
        private final Instant at;
        private Double temperatureC, apparentTemperatureC, dewPointC, windSpeedKmh, windGustKmh;
        private Double precipitationMm, pressureMslHpa, visibilityM, uvIndex;
        private Integer humidityPct, windDirectionDeg, precipitationProbabilityPct, cloudCoverPct;
        private Boolean daytime;
        private String condition;
        private Double vapourPressureDeficitKpa, evapotranspirationMm, soilMoistureSurface, soilMoistureShallow;
        private Double soilMoistureRootZone, soilTemperatureC, boundaryLayerHeightM, capeJkg, liftedIndex;
        private Double convectiveInhibitionJkg, windSpeed80mKmh, shortwaveRadiationWm2, showersMm, snowfallCm;
        private Integer windDirection80mDeg;

        private Builder(Instant at) {
            this.at = at;
        }

        public Builder temperature(Double v) {
            this.temperatureC = v;
            return this;
        }

        public Builder apparent(Double v) {
            this.apparentTemperatureC = v;
            return this;
        }

        public Builder dewPoint(Double v) {
            this.dewPointC = v;
            return this;
        }

        public Builder humidity(Integer v) {
            this.humidityPct = v;
            return this;
        }

        public Builder wind(Double v) {
            this.windSpeedKmh = v;
            return this;
        }

        public Builder windDirection(Integer v) {
            this.windDirectionDeg = v;
            return this;
        }

        public Builder gust(Double v) {
            this.windGustKmh = v;
            return this;
        }

        public Builder precipitation(Double v) {
            this.precipitationMm = v;
            return this;
        }

        public Builder precipitationProbability(Integer v) {
            this.precipitationProbabilityPct = v;
            return this;
        }

        public Builder pressure(Double v) {
            this.pressureMslHpa = v;
            return this;
        }

        public Builder cloud(Integer v) {
            this.cloudCoverPct = v;
            return this;
        }

        public Builder visibility(Double v) {
            this.visibilityM = v;
            return this;
        }

        public Builder uv(Double v) {
            this.uvIndex = v;
            return this;
        }

        public Builder daytime(Boolean v) {
            this.daytime = v;
            return this;
        }

        public Builder condition(String v) {
            this.condition = v;
            return this;
        }

        public Builder vapourPressureDeficit(Double v) {
            this.vapourPressureDeficitKpa = v;
            return this;
        }

        public Builder evapotranspiration(Double v) {
            this.evapotranspirationMm = v;
            return this;
        }

        public Builder soilMoistureSurface(Double v) {
            this.soilMoistureSurface = v;
            return this;
        }

        public Builder soilMoistureShallow(Double v) {
            this.soilMoistureShallow = v;
            return this;
        }

        public Builder soilMoistureRootZone(Double v) {
            this.soilMoistureRootZone = v;
            return this;
        }

        public Builder soilTemperature(Double v) {
            this.soilTemperatureC = v;
            return this;
        }

        public Builder boundaryLayerHeight(Double v) {
            this.boundaryLayerHeightM = v;
            return this;
        }

        public Builder cape(Double v) {
            this.capeJkg = v;
            return this;
        }

        public Builder liftedIndex(Double v) {
            this.liftedIndex = v;
            return this;
        }

        public Builder convectiveInhibition(Double v) {
            this.convectiveInhibitionJkg = v;
            return this;
        }

        public Builder wind80m(Double v) {
            this.windSpeed80mKmh = v;
            return this;
        }

        public Builder windDirection80m(Integer v) {
            this.windDirection80mDeg = v;
            return this;
        }

        public Builder shortwaveRadiation(Double v) {
            this.shortwaveRadiationWm2 = v;
            return this;
        }

        public Builder showers(Double v) {
            this.showersMm = v;
            return this;
        }

        public Builder snowfall(Double v) {
            this.snowfallCm = v;
            return this;
        }

        public Conditions build() {
            return new Conditions(at, temperatureC, apparentTemperatureC, dewPointC, humidityPct, windSpeedKmh,
                    windDirectionDeg, windGustKmh, precipitationMm, precipitationProbabilityPct, pressureMslHpa,
                    cloudCoverPct, visibilityM, uvIndex, daytime, condition,
                    vapourPressureDeficitKpa, evapotranspirationMm, soilMoistureSurface, soilMoistureShallow,
                    soilMoistureRootZone, soilTemperatureC, boundaryLayerHeightM, capeJkg, liftedIndex,
                    convectiveInhibitionJkg, windSpeed80mKmh, windDirection80mDeg, shortwaveRadiationWm2,
                    showersMm, snowfallCm);
        }
    }
}
