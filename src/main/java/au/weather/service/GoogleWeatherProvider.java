package au.weather.service;

import au.weather.http.Fetched;
import au.weather.http.SourceException;
import au.weather.core.Conditions;
import au.weather.core.DayOutlook;
import au.weather.core.WeatherReport;
import au.weather.json.Nodes;
import au.weather.http.HttpFetcher;
import lombok.extern.slf4j.Slf4j;
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
 * The Google Maps Platform Weather API: last in the order, and the only one that bills.
 * <p>
 * It is here for one job. The two free providers ahead of it are free on terms - a daily allowance on
 * one, a licence that excludes commercial use on both - and when either of those runs out the choice
 * is between no weather and paid weather. This is paid weather, held behind the budget so it is only
 * reached when the free allowance is actually gone.
 * <p>
 * <strong>Three requests, not one.</strong> Current conditions, hourly forecast and daily forecast are
 * separate endpoints and separately billed, so one report here costs three calls against a free
 * allowance of 10 000 a month per endpoint. That is what {@code call-weight: 3} in the configuration
 * means, and why this provider is worth avoiding rather than merely worth ranking last.
 */
@Slf4j
@Component
public class GoogleWeatherProvider implements WeatherProvider {

    public static final String ID = "google";

    /**
     * 10 000 free calls per endpoint per month, then USD 0.15 per 1 000. One report is three endpoints,
     * hence the call weight of three, and a longer cooldown: a billed provider that is failing should
     * be left alone for longer than a free one.
     */
    public static final Spec SPEC = Spec.of(ID, "https://weather.googleapis.com/v1", "google-weather-v1",
                    "Weather data from Google Maps Platform", true, 3.0, Limits.perMonth(10_000))
            .withCooldown(Duration.ofMinutes(15));

    /**
     * The one weather value that is genuinely a deployment secret, so the one that stays in the env (D-101).
     */
    private final String apiKey;

    private final WeatherProperties properties;
    private final HttpFetcher fetcher;
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * Written out rather than generated, because the key is the one constructor argument that comes
     * from the environment instead of from the context. Annotating the <em>field</em> and letting
     * Lombok copy the annotation onto the parameter injects it twice - once through the constructor
     * and once by reflection into a final field, which the JDK already warns about and will block.
     */
    public GoogleWeatherProvider(@Value("${GOOGLE_WEATHER_KEY:}") String apiKey,
                                 WeatherProperties properties, HttpFetcher fetcher) {
        this.apiKey = apiKey;
        this.properties = properties;
        this.fetcher = fetcher;
    }

    /**
     * Shared by current conditions and one forecast hour: the two carry the same field names.
     */
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
                // Visibility is reported in kilometres; everything downstream is metres.
                .visibility(kilometresToMetres(Nodes.dbl(Nodes.at(n, "visibility"), "distance")))
                .uv(Nodes.dbl(n, "uvIndex"))
                .daytime(Nodes.bool(n, "isDaytime"))
                .condition(description(Nodes.at(n, "weatherCondition")))
                .build();
    }

    private static DayOutlook day(JsonNode d, ZoneId zone) {
        JsonNode display = Nodes.at(d, "displayDate");
        LocalDate date = display == null ? null : LocalDate.of(
                orZero(Nodes.integer(display, "year")), orOne(Nodes.integer(display, "month")), orOne(Nodes.integer(display, "day")));
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
                instant(Nodes.str(sun, "sunriseTime")),
                instant(Nodes.str(sun, "sunsetTime")),
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

    private static Instant instant(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static int orZero(Integer v) {
        return v == null ? 1970 : v;
    }

    private static int orOne(Integer v) {
        return v == null ? 1 : v;
    }

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public String unavailableReason() {
        return keyed() ? "" : "no API key: set GOOGLE_WEATHER_KEY in .env";
    }

    private boolean keyed() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public WeatherReport fetch(double lat, double lon, Span span) throws SourceException {
        Spec cfg = SPEC;
        String point = "&location.latitude=" + String.format("%.4f", lat)
                + "&location.longitude=" + String.format("%.4f", lon) + "&unitsSystem=METRIC";
        String key = "?key=" + apiKey;

        JsonNode currentNode = read(cfg.endpoint() + "/currentConditions:lookup" + key + point);
        JsonNode hourlyNode = read(cfg.endpoint() + "/forecast/hours:lookup" + key + point
                + "&hours=" + span.hours() + "&pageSize=24");
        JsonNode dailyNode = read(cfg.endpoint() + "/forecast/days:lookup" + key + point
                + "&days=" + span.days() + "&pageSize=" + span.days());

        ZoneId zone = zone(currentNode);
        Conditions current = conditions(currentNode, instant(Nodes.str(currentNode, "currentTime")));

        List<Conditions> hourly = new ArrayList<>();
        JsonNode hours = Nodes.at(hourlyNode, "forecastHours");
        if (hours != null && hours.isArray()) {
            for (JsonNode h : hours) {
                Instant at = instant(Nodes.str(Nodes.at(h, "interval"), "startTime"));
                if (at != null) {
                    hourly.add(conditions(h, at));
                }
            }
        }

        List<DayOutlook> daily = new ArrayList<>();
        JsonNode days = Nodes.at(dailyNode, "forecastDays");
        if (days != null && days.isArray()) {
            for (JsonNode d : days) {
                daily.add(day(d, zone));
            }
        }

        Instant now = Instant.now();
        return new WeatherReport(lat, lon, null, zone.getId(), ID,
                cfg.model(), cfg.attribution(), now,
                now.plus(Duration.ofMinutes(30)), current, hourly, daily);
    }

    private JsonNode read(String url) throws SourceException {
        Fetched fetched = fetcher.get(URI.create(url), null, null);
        JsonNode root = mapper.readTree(fetched.bodyAsString());
        JsonNode error = Nodes.at(root, "error");
        if (error != null) {
            throw new SourceException(ID + ": " + Nodes.str(error, "message"));
        }
        return root;
    }

    private ZoneId zone(JsonNode current) {
        String id = Nodes.str(Nodes.at(current, "timeZone"), "id");
        if (id == null) {
            return properties.zoneId();
        }
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return properties.zoneId();
        }
    }

}
