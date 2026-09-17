package au.weather.service;

import au.weather.http.Fetched;
import au.weather.http.SourceException;
import au.weather.json.Nodes;
import au.weather.http.HttpFetcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily-series half of Open-Meteo: reanalysis history, the recent gap, and river discharge.
 * <p>
 * Separate from {@code OpenMeteoProvider} because it answers a different question. That one asks "what
 * is the weather here now", which is a small, frequent, cacheable request. These are long, rare and
 * expensive: a year of daily rain is several allowance units, and it is fetched once per area per day
 * rather than once per incident.
 * <p>
 * Dates here are ISO strings rather than epochs, deliberately. The hourly path uses {@code unixtime}
 * because instants are unambiguous; a daily aggregate is a <em>local calendar day</em>, and asking for
 * it as an epoch is what produced the off-by-one-day trap recorded in FEEDS 13.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenMeteoDailyClient {

    private final HttpFetcher fetcher;
    private final JsonMapper mapper = JsonMapper.builder().build();

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

    private static String fixed(double v) {
        return String.format("%.4f", v);
    }

    /**
     * Reanalysis history. Lags real time by a few days, which is what {@link #recent} is for.
     */
    public List<DailyRow> archive(String endpoint, double lat, double lon, LocalDate start, LocalDate end) throws SourceException {
        String url = endpoint + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&start_date=" + start + "&end_date=" + end
                + "&daily=precipitation_sum,temperature_2m_max&timezone=auto";
        return daily(read(url, "archive"));
    }

    /**
     * The last few days plus today, from the forecast endpoint, to close the archive gap.
     */
    public List<DailyRow> recent(String endpoint, double lat, double lon, int pastDays) throws SourceException {
        String url = endpoint + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&past_days=" + Math.min(92, Math.max(1, pastDays)) + "&forecast_days=1"
                + "&daily=precipitation_sum,temperature_2m_max&timezone=auto";
        return daily(read(url, "recent"));
    }

    /**
     * Modelled discharge of the largest river within about 5 km, from GloFAS. The past window comes back
     * with the forecast so a baseline and a trend can be read from one call; a river that is running at
     * four times its recent mean is the fact, not the raw cubic metres.
     */
    public List<DischargeRow> discharge(String endpoint, double lat, double lon, int pastDays, int forecastDays) throws SourceException {
        String url = endpoint + "?latitude=" + fixed(lat) + "&longitude=" + fixed(lon)
                + "&daily=river_discharge&past_days=" + Math.min(92, Math.max(0, pastDays))
                + "&forecast_days=" + Math.min(210, Math.max(1, forecastDays));
        JsonNode root = read(url, "flood");
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

    private List<DailyRow> daily(JsonNode root) throws SourceException {
        JsonNode daily = Nodes.at(root, "daily");
        JsonNode times = Nodes.at(daily, "time");
        if (times == null || !times.isArray()) {
            throw new SourceException("open-meteo daily: payload carried no series");
        }
        List<DailyRow> out = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            LocalDate date = date(times.get(i));
            if (date != null) {
                out.add(new DailyRow(date, Nodes.element(daily, "precipitation_sum", i),
                        Nodes.element(daily, "temperature_2m_max", i)));
            }
        }
        return out;
    }

    private JsonNode read(String url, String what) throws SourceException {
        Fetched fetched = fetcher.get(URI.create(url), null, null);
        JsonNode root = mapper.readTree(fetched.bodyAsString());
        if (Boolean.TRUE.equals(Nodes.bool(root, "error"))) {
            throw new SourceException("open-meteo " + what + ": " + Nodes.str(root, "reason"));
        }
        return root;
    }

    /**
     * @param date the local calendar day, @param rainMm total, @param maxTemperatureC the day's maximum
     */
    public record DailyRow(LocalDate date, Double rainMm, Double maxTemperatureC) {
    }

    public record DischargeRow(LocalDate date, Double cumecs) {
    }
}
