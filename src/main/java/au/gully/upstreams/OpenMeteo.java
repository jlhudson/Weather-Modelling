package au.gully.upstreams;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Open-Meteo, the primary: free, keyless, CC BY 4.0, and generous.
 * <p>
 * <strong>What a fetch costs.</strong> Open-Meteo counts variables multiplied by span, not requests:
 * a forecast here is eleven hourly variables over three days, twelve current ones and eleven daily
 * over seven, which its published weighting puts at about three units. (Its elevation endpoint is
 * not used: it counts every point as a call, and a station's terrain is two and a half thousand.)
 */
@Slf4j
@Component
public class OpenMeteo implements Upstream {

    public static final String ID = "open-meteo";
    public static final String FORECAST = "https://api.open-meteo.com/v1/forecast";

    public static final int FORECAST_DAYS = 7;
    public static final int FORECAST_HOURS = 72;
    public static final int PAST_HOURS = 24;

    private static final List<String> CURRENT = List.of("temperature_2m", "relative_humidity_2m",
            "apparent_temperature", "dew_point_2m", "precipitation", "weather_code", "cloud_cover",
            "pressure_msl", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "is_day");

    private static final List<String> HOURLY = List.of("temperature_2m", "relative_humidity_2m",
            "apparent_temperature", "dew_point_2m", "precipitation", "precipitation_probability", "weather_code",
            "cloud_cover", "pressure_msl", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "visibility", "uv_index");

    private static final List<String> DAILY = List.of("weather_code", "temperature_2m_max", "temperature_2m_min",
            "apparent_temperature_max", "sunrise", "sunset", "precipitation_sum", "wind_speed_10m_max",
            "wind_gusts_10m_max", "wind_direction_10m_dominant", "uv_index_max");

    /**
     * Free: 600 a minute, 5,000 an hour, 10,000 a day, 300,000 a month, in the weighted units above.
     */
    public static final Spec SPEC = new Spec(ID, "api.open-meteo.com", "best_match",
            "Weather data by Open-Meteo.com, CC BY 4.0", 3.0,
            new Limits(600, 5_000, 10_000, 300_000), 0.9, 300, false, Duration.ofMinutes(5));

    private final HttpFetcher http;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenMeteo(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public Spec spec() {
        return SPEC;
    }

    /**
     * Open-Meteo says which window ran out, so the pause is that window: the minute, the hour, or
     * the rest of the UTC day.
     */
    @Override
    public Duration pauseAfter(String detail, int status) {
        return pauseFor(detail, Instant.now(), SPEC.pauseAfterFailure());
    }

    static Duration pauseFor(String failureDetail, Instant now, Duration otherwise) {
        String detail = failureDetail == null ? "" : failureDetail.toLowerCase();
        if (!detail.contains("request limit exceeded") && !detail.contains("limit exceeded")) {
            return otherwise;
        }
        if (detail.contains("daily")) {
            Instant midnight = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            return Duration.between(now, midnight).plusMinutes(1);
        }
        if (detail.contains("hourly")) {
            Instant nextHour = now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
            return Duration.between(now, nextHour).plusMinutes(1);
        }
        return Duration.ofMinutes(1);
    }

    @Override
    public Forecast fetch(double lat, double lon) throws UpstreamException {
        String url = FORECAST
                + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&current=" + String.join(",", CURRENT)
                + "&hourly=" + String.join(",", HOURLY)
                + "&daily=" + String.join(",", DAILY)
                + "&timezone=auto&timeformat=unixtime&wind_speed_unit=kmh&precipitation_unit=mm&temperature_unit=celsius"
                + "&forecast_days=" + FORECAST_DAYS
                + "&forecast_hours=" + FORECAST_HOURS + "&past_hours=" + PAST_HOURS;
        return parse(read(url));
    }

    Forecast parse(JsonNode root) throws UpstreamException {
        Integer offsetSeconds = Nodes.integer(root, "utc_offset_seconds");
        String tz = Nodes.str(root, "timezone");
        ZoneId zone = tz == null ? ZoneOffset.UTC : safeZone(tz);
        int offset = offsetSeconds == null ? 0 : offsetSeconds;

        JsonNode currentNode = Nodes.at(root, "current");
        if (currentNode == null) {
            throw new UpstreamException(ID + ": payload carried no current conditions");
        }
        Instant now = Instant.now();
        Conditions current = conditions(currentNode, epoch(Nodes.dbl(currentNode, "time"), now));
        // A model with nothing to say answers 200 with the right shape and every value null. Holding
        // that would be worse than failing, because a held nothing looks like an answer.
        if (current.temperatureC() == null && current.humidityPct() == null && current.windSpeedKmh() == null) {
            throw new UpstreamException(ID + ": model returned no values at this point (all variables null)");
        }

        List<Conditions> hourly = new ArrayList<>();
        JsonNode hourlyNode = Nodes.at(root, "hourly");
        JsonNode times = Nodes.at(hourlyNode, "time");
        if (times != null && times.isArray()) {
            for (int i = 0; i < times.size(); i++) {
                Double t = Nodes.element(hourlyNode, "time", i);
                if (t != null) {
                    hourly.add(series(hourlyNode, i, Instant.ofEpochSecond(t.longValue())));
                }
            }
        }

        List<DayOutlook> daily = new ArrayList<>();
        JsonNode dailyNode = Nodes.at(root, "daily");
        JsonNode days = Nodes.at(dailyNode, "time");
        if (days != null && days.isArray()) {
            for (int i = 0; i < days.size(); i++) {
                Double t = Nodes.element(dailyNode, "time", i);
                if (t == null) {
                    continue;
                }
                // Daily stamps are midnight local expressed as UTC epoch; the offset has to go back on.
                LocalDate date = Instant.ofEpochSecond(t.longValue() + offset).atZone(ZoneOffset.UTC).toLocalDate();
                Integer code = Nodes.elementInt(dailyNode, "weather_code", i);
                List<Conditions> onDay = hourly.stream().filter(c -> c.at().atZone(zone).toLocalDate().equals(date)).toList();
                daily.add(new DayOutlook(date,
                        Nodes.element(dailyNode, "temperature_2m_max", i),
                        Nodes.element(dailyNode, "temperature_2m_min", i),
                        Nodes.element(dailyNode, "apparent_temperature_max", i),
                        onDay.stream().map(Conditions::humidityPct).filter(Objects::nonNull).min(Integer::compareTo).orElse(null),
                        Nodes.element(dailyNode, "wind_speed_10m_max", i),
                        Nodes.element(dailyNode, "wind_gusts_10m_max", i),
                        Nodes.elementInt(dailyNode, "wind_direction_10m_dominant", i),
                        Nodes.element(dailyNode, "precipitation_sum", i),
                        onDay.stream().map(Conditions::precipitationProbabilityPct).filter(Objects::nonNull).max(Integer::compareTo).orElse(null),
                        Nodes.element(dailyNode, "uv_index_max", i),
                        epochOrNull(dailyNode, "sunrise", i),
                        epochOrNull(dailyNode, "sunset", i),
                        WmoCodes.text(code)));
            }
        }

        return new Forecast(ID, SPEC.model(), SPEC.attribution(), now, Nodes.dbl(root, "elevation"), zone.getId(), current, hourly, daily);
    }

    private JsonNode read(String url) throws UpstreamException {
        Fetched fetched = http.get(URI.create(url));
        JsonNode root = mapper.readTree(fetched.bodyAsString());
        // Open-Meteo answers a bad request with 200-shaped JSON carrying error and reason.
        if (Boolean.TRUE.equals(Nodes.bool(root, "error"))) {
            throw new UpstreamException(ID + ": " + Nodes.str(root, "reason"));
        }
        return root;
    }

    // ---------------------------------------------------------------- parsing

    private static Conditions conditions(JsonNode n, Instant at) {
        return Conditions.at(at)
                .temperature(Nodes.dbl(n, "temperature_2m"))
                .apparent(Nodes.dbl(n, "apparent_temperature"))
                .dewPoint(Nodes.dbl(n, "dew_point_2m"))
                .humidity(Nodes.integer(n, "relative_humidity_2m"))
                .wind(Nodes.dbl(n, "wind_speed_10m"))
                .windDirection(Nodes.integer(n, "wind_direction_10m"))
                .gust(Nodes.dbl(n, "wind_gusts_10m"))
                .precipitation(Nodes.dbl(n, "precipitation"))
                .precipitationProbability(Nodes.integer(n, "precipitation_probability"))
                .pressure(Nodes.dbl(n, "pressure_msl"))
                .cloud(Nodes.integer(n, "cloud_cover"))
                .visibility(Nodes.dbl(n, "visibility"))
                .uv(Nodes.dbl(n, "uv_index"))
                .daytime(Nodes.bool(n, "is_day"))
                .condition(WmoCodes.text(Nodes.integer(n, "weather_code")))
                .build();
    }

    private static Conditions series(JsonNode n, int i, Instant at) {
        return Conditions.at(at)
                .temperature(Nodes.element(n, "temperature_2m", i))
                .apparent(Nodes.element(n, "apparent_temperature", i))
                .dewPoint(Nodes.element(n, "dew_point_2m", i))
                .humidity(Nodes.elementInt(n, "relative_humidity_2m", i))
                .wind(Nodes.element(n, "wind_speed_10m", i))
                .windDirection(Nodes.elementInt(n, "wind_direction_10m", i))
                .gust(Nodes.element(n, "wind_gusts_10m", i))
                .precipitation(Nodes.element(n, "precipitation", i))
                .precipitationProbability(Nodes.elementInt(n, "precipitation_probability", i))
                .pressure(Nodes.element(n, "pressure_msl", i))
                .cloud(Nodes.elementInt(n, "cloud_cover", i))
                .visibility(Nodes.element(n, "visibility", i))
                .uv(Nodes.element(n, "uv_index", i))
                .condition(WmoCodes.text(Nodes.elementInt(n, "weather_code", i)))
                .build();
    }

    private static Instant epoch(Double seconds, Instant fallback) {
        return seconds == null ? fallback : Instant.ofEpochSecond(seconds.longValue());
    }

    private static Instant epochOrNull(JsonNode series, String field, int i) {
        Double v = Nodes.element(series, field, i);
        return v == null ? null : Instant.ofEpochSecond(v.longValue());
    }

    private static ZoneId safeZone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    static String fixed(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
