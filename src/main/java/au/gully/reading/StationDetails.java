package au.gully.reading;

import au.gully.bureau.StationRegistry;
import au.gully.bureau.StationsFeed;
import au.gully.reach.Reaches;
import au.gully.record.Droughts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Everything held for one station, as the map's drawer and the API both give it: what it last said and its
 * last readings, its terrain and reach, its drought, and its forecast (W-20) - fetched when older than three
 * hours, since asking for a station is an ask for its forecast.
 */
@Service
@RequiredArgsConstructor
public class StationDetails {

    private final StationsFeed feed;
    private final StationRegistry stations;
    private final Reaches reaches;
    private final Droughts droughts;
    private final Forecasts forecasts;
    private final au.gully.cfs.FireBan fireBan;

    public Optional<Map<String, Object>> of(String id) {
        return stations.station(id).flatMap(s -> feed.detail(id).map(d -> {
            Instant now = Instant.now();
            d.putAll(reaches.detail(id));
            d.putAll(droughts.detail(s));
            d.put("fireBan", fireBan.at(s.lat(), s.lon(), now).orElse(null));
            au.gully.record.Drought dry = droughts.of(s, now).orElse(null);
            d.put("forecast", forecasts.of(s, now, false).map(f -> Forecasts.view(f, s, 0.0, now, dry, dry == null ? null : s)).orElse(null));
            return d;
        }));
    }
}
