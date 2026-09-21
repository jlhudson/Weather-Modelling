package au.gully.upstreams;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Google Maps Platform Weather API: the overflow. When Open-Meteo's allowance for the day is
 * used up, or Open-Meteo is not answering, the fetch goes here instead, at the same point, with
 * the same shape of answer, and its own spend is counted and capped the same way.
 * <p>
 * <strong>Three requests, not one.</strong> Current conditions, hourly and daily are separate
 * endpoints and separately billed, so one fetch costs three units against a free allowance of 10,000
 * a month per endpoint, and past that it bills — which is why it is the overflow and not a peer.
 */
@Component
public class GoogleWeather implements Upstream {

    public static final String ID = "google";
    private static final String ENDPOINT = "https://weather.googleapis.com/v1";

    /**
     * Google publishes no cadence for its current conditions; half an hour is what it has been
     * observed to refresh at and is the life a fetched "now" is given here.
     */

    public static final Spec SPEC = new Spec(ID, "weather.googleapis.com", "google-weather-v1",
            "Weather data from Google Maps Platform", 3.0, new Limits(null, null, null, 10_000), 0.9, 60,
            true, Duration.ofMinutes(15));

    private final String apiKey;
    private final HttpFetcher http;
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * Written out because the key is the one argument that comes from the environment rather than
     * the context (lombok.config: @Value is not copied onto a generated constructor).
     */
    public GoogleWeather(@Value("${GOOGLE_WEATHER_KEY:}") String apiKey, HttpFetcher http) {
        this.apiKey = apiKey;
        this.http = http;
    }

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public String unavailableReason() {
        return apiKey != null && !apiKey.isBlank() ? null : "no API key: set GOOGLE_WEATHER_KEY";
    }

    @Override
    public Forecast fetch(double lat, double lon) throws UpstreamException {
        String point = "&location.latitude=" + OpenMeteo.fixed(lat)
                + "&location.longitude=" + OpenMeteo.fixed(lon) + "&unitsSystem=METRIC";
        String key = "?key=" + apiKey;

        JsonNode currentNode = read(ENDPOINT + "/currentConditions:lookup" + key + point);
        JsonNode hourlyNode = read(ENDPOINT + "/forecast/hours:lookup" + key + point + "&hours=" + OpenMeteo.FORECAST_HOURS + "&pageSize=24");
        JsonNode dailyNode = read(ENDPOINT + "/forecast/days:lookup" + key + point + "&days=" + OpenMeteo.FORECAST_DAYS + "&pageSize=" + OpenMeteo.FORECAST_DAYS);

        ZoneId zone = zone(currentNode);
        Instant now = Instant.now();
        Instant currentAt = instant(Nodes.str(currentNode, "currentTime"), now);
        Conditions current = conditions(currentNode, currentAt);

        List<Conditions> hourly = new ArrayList<>();
        JsonNode hours = Nodes.at(hourlyNode, "forecastHours");
        if (hours != null && hours.isArray()) {
            for (JsonNode h : hours) {
                Instant at = instant(Nodes.str(Nodes.at(h, "interval"), "startTime"), null);
                if (at != null) {
                    hourly.add(conditions(h, at));
                }
            }
        }

        List<DayOutlook> daily = new ArrayList<>();
        JsonNode days = Nodes.at(dailyNode, "forecastDays");
        if (days != null && days.isArray()) {
            for (JsonNode d : days) {
                daily.add(day(d));
            }
        }
        return new Forecast(ID, SPEC.model(), SPEC.attribution(), now, null, zone.getId(), current, hourly, daily);
    }

    private JsonNode read(String url) throws UpstreamException {
        Fetched fetched = http.get(URI.create(url));
        JsonNode root = mapper.readTree(fetched.bodyAsString());
        JsonNode error = Nodes.at(root, "error");
        if (error != null) {
            throw new UpstreamException(ID + ": " + Nodes.str(error, "message"));
        }
        return root;
    }

    private static Conditions conditions(JsonNode n, Instant at) {
        JsonNode wind = Nodes.at(n, "wind");
        JsonNode precipitation = Nodes.at(n, "precipitation");
        return Conditions.at(at)
                .temperature(degrees(n, "temperature"))
                .apparent(degrees(n, "feelsLikeTemperature"))
                .dewPoint(degrees(n, "dewPoint"))
                .humidity(Nodes.integer(n, "relativeHumidity"))
                .wind(Nodes.dbl(Nodes.at(wind, "speed"), "value"))
                .windDirection(Nodes.integer(Nodes.at(wind, "direction"), "degrees"))
                .gust(Nodes.dbl(Nodes.at(wind, "gust"), "value"))
                .precipitation(Nodes.dbl(Nodes.at(precipitation, "qpf"), "quantity"))
                .precipitationProbability(Nodes.integer(Nodes.at(precipitation, "probability"), "percent"))
                .pressure(Nodes.dbl(Nodes.at(n, "airPressure"), "meanSeaLevelMillibars"))
                .cloud(Nodes.integer(n, "cloudCover"))
                .visibility(kilometresToMetres(Nodes.dbl(Nodes.at(n, "visibility"), "distance")))
                .uv(Nodes.dbl(n, "uvIndex"))
                .daytime(Nodes.bool(n, "isDaytime"))
                .condition(description(Nodes.at(n, "weatherCondition")))
                .build();
    }

    private static DayOutlook day(JsonNode d) {
        JsonNode display = Nodes.at(d, "displayDate");
        LocalDate date = display == null ? null : LocalDate.of(
                orDefault(Nodes.integer(display, "year"), 1970), orDefault(Nodes.integer(display, "month"), 1),
                orDefault(Nodes.integer(display, "day"), 1));
        JsonNode daytime = Nodes.at(d, "daytimeForecast");
        JsonNode wind = Nodes.at(daytime, "wind");
        JsonNode precipitation = Nodes.at(daytime, "precipitation");
        JsonNode sun = Nodes.at(d, "sunEvents");
        return new DayOutlook(date,
                degrees(d, "maxTemperature"),
                degrees(d, "minTemperature"),
                degrees(d, "feelsLikeMaxTemperature"),
                Nodes.integer(daytime, "relativeHumidity"),
                Nodes.dbl(Nodes.at(wind, "speed"), "value"),
                Nodes.dbl(Nodes.at(wind, "gust"), "value"),
                Nodes.integer(Nodes.at(wind, "direction"), "degrees"),
                Nodes.dbl(Nodes.at(precipitation, "qpf"), "quantity"),
                Nodes.integer(Nodes.at(precipitation, "probability"), "percent"),
                Nodes.dbl(daytime, "uvIndex"),
                instant(Nodes.str(sun, "sunriseTime"), null),
                instant(Nodes.str(sun, "sunsetTime"), null),
                description(Nodes.at(daytime, "weatherCondition")));
    }

    private static Double degrees(JsonNode parent, String field) {
        return Nodes.dbl(Nodes.at(parent, field), "degrees");
    }

    private static Double kilometresToMetres(Double km) {
        return km == null ? null : km * 1000.0;
    }

    private static String description(JsonNode condition) {
        String text = Nodes.str(Nodes.at(condition, "description"), "text");
        return text != null ? text : Nodes.str(condition, "type");
    }

    private static Instant instant(String iso, Instant fallback) {
        if (iso == null) {
            return fallback;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static int orDefault(Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    private static ZoneId zone(JsonNode current) {
        String id = Nodes.str(Nodes.at(current, "timeZone"), "id");
        try {
            return id == null ? ZoneId.of("Australia/Adelaide") : ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneId.of("Australia/Adelaide");
        }
    }
}
