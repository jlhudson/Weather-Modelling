package au.weather.service;

import au.weather.http.Fetched;
import au.weather.http.SourceException;
import au.weather.core.Conditions;
import au.weather.core.DayOutlook;
import au.weather.core.Hourly;
import au.weather.core.WeatherReport;
import au.weather.json.Nodes;
import au.weather.http.HttpFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Open-Meteo, the primary. Free, keyless, CC-BY 4.0, and generous enough that a system anchoring its
 * lookups the way this one does will never approach the ceiling: 10 000 calls a day against a few
 * hundred anchors an hour on the worst day imaginable.
 * <p>
 * One class serves two configured providers, because the Australian one is the same API at a different
 * path. {@code /v1/forecast} is the blended best-available model; {@code /v1/bom} is the Bureau of
 * Meteorology ACCESS-G model, which is the same national model the Bureau runs its own forecasts from
 * - reached over a documented free API instead of an FTP drop of XML files.
 * <p>
 * <strong>Call weighting.</strong> Open-Meteo does not count requests, it counts variables multiplied
 * by span: a fortnight of fifteen variables is 1.5 calls. Keeping the forecast to a few days keeps one
 * report at one call, which is why {@code forecastDays} is small and configurable rather than maximal.
 */
@Slf4j
@Component
public class OpenMeteoProvider implements WeatherProvider {

    /**
     * Free: 600 a minute, 5 000 an hour, 10 000 a day, 300 000 a month. No key. CC BY 4.0, which
     * excludes commercial use - the console says so, and a commercial deployment drops it from the order.
     */
    private static final Limits FREE = new Limits(600, 5000, 10_000, 300_000);

    /**
     * The blended best-available model: the widest variable set, and the default first choice.
     */
    public static final Spec GLOBAL = Spec.of("open-meteo", "https://api.open-meteo.com/v1/forecast",
                    "best_match", "Weather data by Open-Meteo.com, CC BY 4.0", false, 1.0, FREE)
            .withExtras(List.of("visibility", "uv_index", "precipitation_probability", "cape", "lifted_index",
                    "convective_inhibition", "vapour_pressure_deficit", "et0_fao_evapotranspiration",
                    "boundary_layer_height", "soil_moisture_0_to_1cm", "soil_moisture_3_to_9cm",
                    "soil_moisture_27_to_81cm", "soil_temperature_0cm", "wind_speed_80m", "wind_direction_80m",
                    "shortwave_radiation", "showers"));

    /**
     * The same API against the Bureau of Meteorology ACCESS-G model: the Australian national model as
     * JSON, which is what replaced the FTP ingest of Bureau XML products. Fewer variables.
     *
     * <p>Not in the default order, and that is not an oversight. On 5 September 2026 the endpoint
     * answered 200 with the right shape and every value null, which matches the Bureau having suspended
     * open-data delivery during its platform upgrade. Add {@code open-meteo-bom} to
     * {@code weather.order} when the Bureau restores the feed: the provider rejects an all-null
     * payload rather than caching it, so a premature switch costs one failed call and a cooldown, not
     * a wrong answer.
     */
    public static final Spec BOM = Spec.of("open-meteo-bom", "https://api.open-meteo.com/v1/bom",
                    "bom_access_global", "BOM ACCESS-G via Open-Meteo.com, CC BY 4.0", false, 1.0, FREE)
            .withExtras(List.of("cape"));

    /**
     * Variables every Open-Meteo model carries. Anything model-specific belongs in the spec's extras.
     */
    private static final List<String> CURRENT = List.of("temperature_2m", "relative_humidity_2m",
            "apparent_temperature", "dew_point_2m", "precipitation", "weather_code", "cloud_cover",
            "pressure_msl", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "is_day");

    private static final List<String> HOURLY = List.of("temperature_2m", "relative_humidity_2m",
            "apparent_temperature", "dew_point_2m", "precipitation", "weather_code", "cloud_cover",
            "pressure_msl", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m");

    private static final List<String> DAILY = List.of("weather_code", "temperature_2m_max", "temperature_2m_min",
            "apparent_temperature_max", "sunrise", "sunset", "precipitation_sum", "wind_speed_10m_max",
            "wind_gusts_10m_max", "wind_direction_10m_dominant");

    private final Spec spec;
    private final WeatherProperties properties;
    private final HttpFetcher fetcher;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    public OpenMeteoProvider(WeatherProperties properties, HttpFetcher fetcher) {
        this(GLOBAL, properties, fetcher);
    }

    protected OpenMeteoProvider(Spec spec, WeatherProperties properties, HttpFetcher fetcher) {
        this.spec = spec;
        this.properties = properties;
        this.fetcher = fetcher;
    }

    static Duration cooldownFor(String failureDetail, Instant now, Duration otherwise) {
        String detail = failureDetail == null ? "" : failureDetail.toLowerCase();
        if (!detail.contains("request limit exceeded")) {
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
        return Duration.ofMinutes(1); // the minute's limit, the only one our own pacing can reach
    }

    private static Instant epochOrNull(JsonNode series, String field, int i) {
        Double v = Nodes.element(series, field, i);
        return v == null ? null : Instant.ofEpochSecond(v.longValue());
    }

    private static long longOf(JsonNode n, String field) {
        Double v = Nodes.dbl(n, field);
        return v == null ? Instant.now().getEpochSecond() : v.longValue();
    }

    private static ZoneId safeZone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String fixed(double v) {
        return String.format("%.4f", v);
    }

    @Override
    public Spec spec() {
        return spec;
    }

    /**
     * Open-Meteo says which window ran out, so the cooldown is that window and not the generic five
     * minutes. The limits are counted against the calling address, not this application: on 12 September
     * 2026 the very first call after a restart was refused with the daily limit exhausted while the
     * ledger held a hundred units, so whatever shares this connection had spent the day. That is a fact
     * about the address the ledger cannot see and cannot guard against; what it can do is stop retrying a
     * day's limit every five minutes, which was fourteen refused round trips and a six-unit archive fetch
     * discarded on each of them. Open-Meteo counts its day in UTC and the message says "tomorrow", so the
     * daily cooldown runs to the next UTC midnight; if that reading is wrong, one refused call re-arms it.
     */
    @Override
    public Duration cooldownAfter(String failureDetail) {
        return cooldownFor(failureDetail, Instant.now(), spec.cooldownAfterFailure());
    }

    @Override
    public WeatherReport fetch(double lat, double lon, Span span) throws SourceException {
        Spec cfg = spec;
        Set<String> current = new LinkedHashSet<>(CURRENT);
        Set<String> hourly = new LinkedHashSet<>(HOURLY);
        current.addAll(cfg.extras());
        hourly.addAll(cfg.extras());

        String url = cfg.endpoint()
                + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&current=" + String.join(",", current)
                + "&hourly=" + String.join(",", hourly)
                + "&daily=" + String.join(",", DAILY)
                + "&timezone=auto&timeformat=unixtime&wind_speed_unit=kmh&precipitation_unit=mm&temperature_unit=celsius"
                + "&forecast_days=" + span.days()
                + "&forecast_hours=" + span.hours();

        Fetched fetched = fetcher.get(URI.create(url), null, null);
        JsonNode root = mapper.readTree(fetched.bodyAsString());
        // Open-Meteo answers a bad variable name with 200-shaped JSON carrying error and reason.
        Boolean error = Nodes.bool(root, "error");
        if (Boolean.TRUE.equals(error)) {
            throw new SourceException(id() + ": " + Nodes.str(root, "reason"));
        }
        return parse(root, lat, lon, cfg);
    }

    WeatherReport parse(JsonNode root, double requestedLat, double requestedLon, Spec cfg) throws SourceException {
        Double lat = Nodes.dbl(root, "latitude");
        Double lon = Nodes.dbl(root, "longitude");
        Integer offsetSeconds = Nodes.integer(root, "utc_offset_seconds");
        String tz = Nodes.str(root, "timezone");
        ZoneId zone = tz == null ? properties.zoneId() : safeZone(tz);
        int offset = offsetSeconds == null ? 0 : offsetSeconds;

        JsonNode currentNode = Nodes.at(root, "current");
        if (currentNode == null) {
            throw new SourceException(id() + ": payload carried no current conditions");
        }
        Conditions current = conditions(currentNode, Instant.ofEpochSecond(longOf(currentNode, "time")));
        // A model with nothing to say answers 200 with the right shape and every value null - which is
        // exactly what the BOM ACCESS-G endpoint does while the Bureau has open-data delivery suspended.
        // Caching that would be worse than failing, because a cached nothing looks like an answer.
        if (current.temperatureC() == null && current.humidityPct() == null && current.windSpeedKmh() == null) {
            throw new SourceException(id() + ": model returned no values at this point (all variables null)");
        }

        List<Conditions> hourly = new ArrayList<>();
        JsonNode hourlyNode = Nodes.at(root, "hourly");
        JsonNode times = Nodes.at(hourlyNode, "time");
        if (times != null && times.isArray()) {
            for (int i = 0; i < times.size(); i++) {
                Double t = Nodes.element(hourlyNode, "time", i);
                if (t == null) {
                    continue;
                }
                hourly.add(series(hourlyNode, i, Instant.ofEpochSecond(t.longValue())));
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
                daily.add(new DayOutlook(date,
                        Nodes.element(dailyNode, "temperature_2m_max", i),
                        Nodes.element(dailyNode, "temperature_2m_min", i),
                        Nodes.element(dailyNode, "apparent_temperature_max", i),
                        Hourly.minHumidityOn(hourly, date, zone),
                        Nodes.element(dailyNode, "wind_speed_10m_max", i),
                        Nodes.element(dailyNode, "wind_gusts_10m_max", i),
                        Nodes.elementInt(dailyNode, "wind_direction_10m_dominant", i),
                        Nodes.element(dailyNode, "precipitation_sum", i),
                        Hourly.maxProbabilityOn(hourly, date, zone),
                        Nodes.element(dailyNode, "uv_index_max", i),
                        epochOrNull(dailyNode, "sunrise", i),
                        epochOrNull(dailyNode, "sunset", i),
                        WmoCodes.text(code)));
            }
        }

        Instant now = Instant.now();
        return new WeatherReport(lat == null ? requestedLat : lat, lon == null ? requestedLon : lon,
                Nodes.dbl(root, "elevation"), zone.getId(), id(), cfg.model(),
                cfg.attribution(), now, now.plus(Duration.ofMinutes(15)), current, hourly, daily);
    }

    private Conditions conditions(JsonNode n, Instant at) {
        Integer code = Nodes.integer(n, "weather_code");
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
                .condition(WmoCodes.text(code))
                // Fire and flood. Requested through `extras`, so a model that does not carry one simply
                // leaves it null rather than failing the whole request.
                .vapourPressureDeficit(Nodes.dbl(n, "vapour_pressure_deficit"))
                .evapotranspiration(Nodes.dbl(n, "et0_fao_evapotranspiration"))
                .soilMoistureSurface(Nodes.dbl(n, "soil_moisture_0_to_1cm"))
                .soilMoistureShallow(Nodes.dbl(n, "soil_moisture_3_to_9cm"))
                .soilMoistureRootZone(Nodes.dbl(n, "soil_moisture_27_to_81cm"))
                .soilTemperature(Nodes.dbl(n, "soil_temperature_0cm"))
                .boundaryLayerHeight(Nodes.dbl(n, "boundary_layer_height"))
                .cape(Nodes.dbl(n, "cape"))
                .liftedIndex(Nodes.dbl(n, "lifted_index"))
                .convectiveInhibition(Nodes.dbl(n, "convective_inhibition"))
                .wind80m(Nodes.dbl(n, "wind_speed_80m"))
                .windDirection80m(Nodes.integer(n, "wind_direction_80m"))
                .shortwaveRadiation(Nodes.dbl(n, "shortwave_radiation"))
                .showers(Nodes.dbl(n, "showers"))
                .snowfall(Nodes.dbl(n, "snowfall"))
                .build();
    }

    private Conditions series(JsonNode n, int i, Instant at) {
        Integer code = Nodes.elementInt(n, "weather_code", i);
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
                .condition(WmoCodes.text(code))
                .vapourPressureDeficit(Nodes.element(n, "vapour_pressure_deficit", i))
                .evapotranspiration(Nodes.element(n, "et0_fao_evapotranspiration", i))
                .soilMoistureSurface(Nodes.element(n, "soil_moisture_0_to_1cm", i))
                .soilMoistureShallow(Nodes.element(n, "soil_moisture_3_to_9cm", i))
                .soilMoistureRootZone(Nodes.element(n, "soil_moisture_27_to_81cm", i))
                .soilTemperature(Nodes.element(n, "soil_temperature_0cm", i))
                .boundaryLayerHeight(Nodes.element(n, "boundary_layer_height", i))
                .cape(Nodes.element(n, "cape", i))
                .liftedIndex(Nodes.element(n, "lifted_index", i))
                .convectiveInhibition(Nodes.element(n, "convective_inhibition", i))
                .wind80m(Nodes.element(n, "wind_speed_80m", i))
                .windDirection80m(Nodes.elementInt(n, "wind_direction_80m", i))
                .shortwaveRadiation(Nodes.element(n, "shortwave_radiation", i))
                .showers(Nodes.element(n, "showers", i))
                .snowfall(Nodes.element(n, "snowfall", i))
                .build();
    }

}
