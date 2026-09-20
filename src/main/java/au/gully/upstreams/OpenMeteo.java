package au.gully.upstreams;

import au.gully.hexagons.Cell;
import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.Nodes;
import au.gully.platform.UpstreamException;
import au.gully.science.Conditions;
import au.gully.science.DayOutlook;
import au.gully.science.Hourly;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Open-Meteo, the primary: free, keyless, CC BY 4.0, and generous. One class for its four endpoints,
 * because they share a host, a licence and an allowance: the forecast (the reading), the reanalysis
 * archive and the recent-days call (the drought spin-up, docs/06 item 7), and GloFAS river discharge
 * (the flood block).
 * <p>
 * <strong>What a fetch costs.</strong> Open-Meteo counts variables multiplied by span, not requests:
 * this fetch is thirty-odd hourly variables over three days, twelve current ones and ten daily over
 * seven, which its published weighting puts at about five units. The ledger charges five; the
 * console shows the day's total against the ten thousand.
 * <p>
 * <strong>What an answer is good for</strong> is not the upstream's to say: the service keeps a forecast
 * for three hours (five when the allowance is tight), throws it out early when the station in the
 * hexagon says the model has drifted, and reads "now" off the hourly series at any moment inside that
 * life ({@code Forecast#at}). The current block is the model's "now" at the moment of the fetch.
 */
@Slf4j
@Component
public class OpenMeteo implements Upstream {

    public static final String ID = "open-meteo";
    public static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    public static final String ARCHIVE = "https://archive-api.open-meteo.com/v1/archive";
    public static final String FLOOD = "https://flood-api.open-meteo.com/v1/flood";
    public static final String ELEVATION = "https://api.open-meteo.com/v1/elevation";

    public static final int FORECAST_DAYS = 7;
    public static final int FORECAST_HOURS = 72;

    /**
     * The hours behind now the series also carries: enough to reach back to 9 am local from any hour of
     * the day, which is what the rain comparison against a station needs ({@code Drift}).
     */
    public static final int PAST_HOURS = 24;

    /**
     * The archive fetch behind a drought spin-up: a year of daily rain and temperature. Open-Meteo's
     * published weighting counts a fortnight of up to ten variables as one call, so 365 days is 26,
     * whatever the number of variables under ten - and a burst of spin-ups charged at six met the
     * minute limit long before the ledger said so.
     */
    public static final double ARCHIVE_UNITS = 26.0;
    /** A few past days from the forecast endpoint, and one river discharge series. */
    public static final double SMALL_UNITS = 1.0;
    /**
     * How long the archive is given to answer. A year of reanalysis at one point usually comes back in
     * a second and sometimes in fifteen; the thirty seconds every other read gets was cutting off one
     * in twenty, and a spin-up that loses its year waits a quarter of an hour to ask again.
     */
    public static final Duration ARCHIVE_PATIENCE = Duration.ofSeconds(90);


    private static final List<String> CURRENT = List.of("temperature_2m", "relative_humidity_2m",
            "apparent_temperature", "dew_point_2m", "precipitation", "weather_code", "cloud_cover",
            "pressure_msl", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "is_day");

    private static final List<String> HOURLY = List.of("temperature_2m", "relative_humidity_2m",
            "apparent_temperature", "dew_point_2m", "precipitation", "weather_code", "cloud_cover",
            "pressure_msl", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m");

    private static final List<String> EXTRAS = List.of("visibility", "uv_index", "precipitation_probability", "cape",
            "lifted_index", "convective_inhibition", "vapour_pressure_deficit", "et0_fao_evapotranspiration",
            "boundary_layer_height", "soil_moisture_0_to_1cm", "soil_moisture_3_to_9cm",
            "soil_moisture_27_to_81cm", "soil_temperature_0cm", "wind_speed_80m", "wind_direction_80m",
            "shortwave_radiation", "showers");

    private static final List<String> DAILY = List.of("weather_code", "temperature_2m_max", "temperature_2m_min",
            "apparent_temperature_max", "sunrise", "sunset", "precipitation_sum", "wind_speed_10m_max",
            "wind_gusts_10m_max", "wind_direction_10m_dominant", "uv_index_max");

    /**
     * Free: 600 a minute, 5,000 an hour, 10,000 a day, 300,000 a month, in the weighted units above.
     */
    public static final Spec SPEC = new Spec(ID, "api.open-meteo.com", "best_match",
            "Weather data by Open-Meteo.com, CC BY 4.0", 5.0,
            new Limits(600, 5_000, 10_000, 300_000), 0.9, 300, false, Duration.ofMinutes(5), EXTRAS);

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
    public Forecast fetch(Cell cell) throws UpstreamException {
        List<String> current = new ArrayList<>(CURRENT);
        List<String> hourly = new ArrayList<>(HOURLY);
        current.addAll(EXTRAS);
        hourly.addAll(EXTRAS);
        String url = FORECAST
                + "?latitude=" + fixed(cell.lat()) + "&longitude=" + fixed(cell.lon())
                + "&current=" + String.join(",", current)
                + "&hourly=" + String.join(",", hourly)
                + "&daily=" + String.join(",", DAILY)
                + "&timezone=auto&timeformat=unixtime&wind_speed_unit=kmh&precipitation_unit=mm&temperature_unit=celsius"
                + "&forecast_days=" + FORECAST_DAYS
                + "&forecast_hours=" + FORECAST_HOURS + "&past_hours=" + PAST_HOURS;
        JsonNode root = read(url);
        return parse(root);
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
        Instant currentAt = epoch(Nodes.dbl(currentNode, "time"), now);
        Conditions current = conditions(currentNode, currentAt);
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

        return new Forecast(ID, SPEC.model(), SPEC.attribution(), now, Nodes.dbl(root, "elevation"), zone.getId(), current, hourly, daily);
    }

    // ---------------------------------------------------------------- the daily series

    /**
     * Reanalysis history: hourly rain and temperature, which lags real time by a few days, summed
     * and maxed into the Bureau's rain day - 9 am to 9 am local - so the archive's days and the
     * stations' are the same days (W-21). The start is asked a day early, since the first rain day
     * begins at 9 am the day before.
     */
    public List<DailyRow> archive(double lat, double lon, LocalDate start, LocalDate end) throws UpstreamException {
        String url = ARCHIVE + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&start_date=" + start.minusDays(1) + "&end_date=" + end.plusDays(1)
                + "&hourly=precipitation,temperature_2m&timezone=auto&timeformat=unixtime";
        return rainDays(read(url, ARCHIVE_PATIENCE), start, end, null);
    }

    /**
     * The ground height at up to a hundred points, from Open-Meteo's elevation endpoint (a 90 m digital
     * elevation model): what a hexagon's mean elevation is read from when no terrain file is mounted,
     * one call per hexagon, once. Null where the model has nothing (the sea).
     */
    public List<Double> elevation(List<double[]> points) throws UpstreamException {
        StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
        for (double[] p : points) {
            lats.append(lats.isEmpty() ? "" : ",").append(fixed(p[0]));
            lons.append(lons.isEmpty() ? "" : ",").append(fixed(p[1]));
        }
        JsonNode root = read(ELEVATION + "?latitude=" + lats + "&longitude=" + lons);
        JsonNode values = Nodes.at(root, "elevation");
        List<Double> out = new ArrayList<>();
        if (values != null && values.isArray()) {
            for (JsonNode v : values) {
                out.add(v == null || v.isNull() ? null : v.asDouble());
            }
        }
        return out;
    }

    /**
     * The last few days, from the forecast endpoint, to close the archive's gap: the same hourly
     * series summed into 9 am days, and only the days already complete - a rain day still running
     * is not a day. Asked a day further back than wanted, for the first day's 9 am start.
     */
    public List<DailyRow> recent(double lat, double lon, int pastDays) throws UpstreamException {
        String url = FORECAST + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&past_days=" + Math.min(92, Math.max(1, pastDays + 1)) + "&forecast_days=1"
                + "&hourly=precipitation,temperature_2m&timezone=auto&timeformat=unixtime";
        return rainDays(read(url), null, null, Instant.now());
    }

    /**
     * Modelled discharge of the largest river within about 5 km, from GloFAS, with the past window so
     * a baseline and a trend can be read from one call.
     */
    public List<DischargeRow> discharge(double lat, double lon, int pastDays, int forecastDays) throws UpstreamException {
        String url = FLOOD + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&daily=river_discharge&past_days=" + Math.min(92, Math.max(0, pastDays))
                + "&forecast_days=" + Math.min(210, Math.max(1, forecastDays));
        JsonNode root = read(url);
        JsonNode daily = Nodes.at(root, "daily");
        JsonNode times = Nodes.at(daily, "time");
        List<DischargeRow> out = new ArrayList<>();
        if (times == null || !times.isArray()) {
            return out;
        }
        for (int i = 0; i < times.size(); i++) {
            LocalDate date = date(times.get(i));
            if (date != null) {
                out.add(new DischargeRow(date, Nodes.element(daily, "river_discharge", i)));
            }
        }
        return out;
    }

    /**
     * The hour the Bureau's rain day turns: the 24 hours to 9 am local are the day before's rain.
     */
    static final int RAIN_DAY_TURNS_AT = 9;

    /**
     * An hourly series of rain and temperature summed and maxed into rain days (W-21): the hour
     * beginning at 9 am on day D through the hour beginning at 8 am on D+1 is day D. A day is kept
     * only when all twenty-four of its hours are there and, given a {@code now}, all are in the past.
     * Days outside {@code from..to} are dropped when a range is given.
     */
    static List<DailyRow> rainDays(JsonNode root, LocalDate from, LocalDate to, Instant now) throws UpstreamException {
        JsonNode hourly = Nodes.at(root, "hourly");
        JsonNode times = Nodes.at(hourly, "time");
        if (times == null || !times.isArray()) {
            throw new UpstreamException(ID + " hourly: payload carried no series");
        }
        String tz = Nodes.str(root, "timezone");
        ZoneId zone = tz == null ? ZoneOffset.UTC : safeZone(tz);
        java.util.SortedMap<LocalDate, double[]> days = new java.util.TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            Double t = Nodes.element(hourly, "time", i);
            Double rain = Nodes.element(hourly, "precipitation", i);
            Double temp = Nodes.element(hourly, "temperature_2m", i);
            if (t == null || rain == null || temp == null) {
                continue;
            }
            Instant at = Instant.ofEpochSecond(t.longValue());
            if (now != null && !at.plusSeconds(3600).isBefore(now.plusSeconds(1))) {
                continue;
            }
            java.time.ZonedDateTime local = at.atZone(zone);
            LocalDate day = local.getHour() < RAIN_DAY_TURNS_AT ? local.toLocalDate().minusDays(1) : local.toLocalDate();
            double[] acc = days.computeIfAbsent(day, k -> new double[]{0, Double.NEGATIVE_INFINITY, 0});
            acc[0] += rain;
            acc[1] = Math.max(acc[1], temp);
            acc[2]++;
        }
        List<DailyRow> out = new ArrayList<>();
        days.forEach((day, acc) -> {
            if (acc[2] < 24 || (from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) {
                return;
            }
            out.add(new DailyRow(day, Math.round(acc[0] * 10) / 10.0, Math.round(acc[1] * 10) / 10.0));
        });
        return out;
    }

    private JsonNode read(String url) throws UpstreamException {
        return read(http.get(URI.create(url)));
    }

    private JsonNode read(String url, Duration patience) throws UpstreamException {
        return read(http.get(URI.create(url), patience));
    }

    private JsonNode read(Fetched fetched) throws UpstreamException {
        JsonNode root = mapper.readTree(fetched.bodyAsString());
        // Open-Meteo answers a bad request with 200-shaped JSON carrying error and reason.
        if (Boolean.TRUE.equals(Nodes.bool(root, "error"))) {
            throw new UpstreamException(ID + ": " + Nodes.str(root, "reason"));
        }
        return root;
    }

    // ---------------------------------------------------------------- parsing

    private static Conditions conditions(JsonNode n, Instant at) {
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

    private static Conditions series(JsonNode n, int i, Instant at) {
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

    private static Instant epoch(Double seconds, Instant fallback) {
        return seconds == null ? fallback : Instant.ofEpochSecond(seconds.longValue());
    }

    private static Instant epochOrNull(JsonNode series, String field, int i) {
        Double v = Nodes.element(series, field, i);
        return v == null ? null : Instant.ofEpochSecond(v.longValue());
    }

    private static LocalDate date(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return LocalDate.parse(n.asText().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ZoneId safeZone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String fixed(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /**
     * One day of history: the Bureau's rain day - the 24 hours from 9 am local on the date - its rain
     * and its maximum.
     */
    public record DailyRow(LocalDate date, Double rainMm, Double maxTemperatureC) {
    }

    public record DischargeRow(LocalDate date, Double cumecs) {
    }
}
