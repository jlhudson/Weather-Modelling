package au.gully.upstreams;

import java.time.Instant;

/**
 * The weather at one point at one moment, in one shape whichever upstream produced it.
 * <p>
 * Every field is nullable and every field is SI with Australian habits: Celsius, km/h, millimetres,
 * hPa, per cent, metres. An upstream that does not carry a variable leaves it null rather than
 * substituting a zero, because a zero wind and an unknown wind are different facts.
 *
 * @param at           the instant these conditions describe, not the instant they were fetched
 * @param humidityPct  relative humidity at 2 m
 * @param windSpeedKmh 10 m mean wind
 * @param windGustKmh  10 m gust
 * @param condition    the upstream's own words for the sky, kept verbatim and never parsed
 */
public record Conditions(
        Instant at,
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
        String condition
) {

    public static Builder at(Instant at) {
        return new Builder(at);
    }

    /**
     * Mutable while an upstream fills it in from a payload whose fields arrive in no useful order.
     */
    public static final class Builder {
        private final Instant at;
        private Double temperatureC, apparentTemperatureC, dewPointC, windSpeedKmh, windGustKmh;
        private Double precipitationMm, pressureMslHpa, visibilityM, uvIndex;
        private Integer humidityPct, windDirectionDeg, precipitationProbabilityPct, cloudCoverPct;
        private Boolean daytime;
        private String condition;

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

        public Conditions build() {
            return new Conditions(at, temperatureC, apparentTemperatureC, dewPointC, humidityPct, windSpeedKmh,
                    windDirectionDeg, windGustKmh, precipitationMm, precipitationProbabilityPct, pressureMslHpa,
                    cloudCoverPct, visibilityM, uvIndex, daytime, condition);
        }
    }
}
