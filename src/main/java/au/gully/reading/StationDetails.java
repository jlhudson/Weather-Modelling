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
    private final au.gully.cfs.Curing curing;
    private final au.gully.bureau.Warnings warnings;
    private final au.gully.record.Record record;
    private final Rivers rivers;

    public Optional<Map<String, Object>> of(String id) {
        return stations.station(id).flatMap(s -> feed.detail(id).map(d -> {
            Instant now = Instant.now();
            d.putAll(reaches.detail(id));
            d.putAll(droughts.detail(s));
            Map<String, Object> ban = fireBan.at(s.lat(), s.lon(), now).orElse(null);
            d.put("fireBan", ban);
            java.util.List<String> aacs = new java.util.ArrayList<>();
            if (s.district() != null) {
                aacs.add(s.district());
            }
            if (ban != null && ban.get("aac") != null) {
                aacs.add((String) ban.get("aac"));
            }
            d.put("warnings", Readings.warningsView(warnings.at(aacs, now), aacs, warnings.readAt()));
            String district = ban == null ? null : (String) ban.get("district");
            au.gully.record.Drought dry = droughts.of(s, now).orElse(null);
            Forecasts.FireInputs in = new Forecasts.FireInputs(dry, dry == null ? null : s, district, district == null ? null : curing.of(district).orElse(null));
            au.gully.upstreams.Forecast f = forecasts.of(s, now, false).orElse(null);
            d.put("forecast", f == null ? null : Forecasts.view(f, s, 0.0, now, in));
            java.time.ZoneId zone = au.gully.record.Record.zoneOf(s);
            d.put("flood", Flood.block(s, record.days(s.id()), record.rainSoFar(s, now), au.gully.record.Record.dayOf(now, zone), f, now,
                    rivers.at(s.lat(), s.lon(), now, now.atZone(zone).toLocalDate()).orElse(null)));
            return d;
        }));
    }
}
