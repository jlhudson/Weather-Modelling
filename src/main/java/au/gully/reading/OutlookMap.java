package au.gully.reading;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.cfs.Curing;
import au.gully.cfs.FireDistricts;
import au.gully.record.Droughts;
import au.gully.upstreams.Forecast;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The fire outlook for the whole map (W-31): every Bureau station's worst forecast hour for today and the next two days -
 * the forest index, and the grass indices where its district has a curing figure. Colouring the map by it is an ask for
 * every station's forecast, so what is held is answered at once and any station whose forecast is missing or older than
 * {@link Forecasts#LIFE} is fetched in the background, {@link #AT_ONCE} at a time; the map asks again until nothing is
 * pending. Nothing runs on a clock (W-15): only while someone is looking.
 */
@Slf4j
@Service
public class OutlookMap {

    public static final int AT_ONCE = 4;

    private final StationRegistry stations;
    private final Forecasts forecasts;
    private final Droughts droughts;
    private final FireDistricts districts;
    private final au.gully.cfs.FireBan fireBan;
    private final Curing curing;
    private final au.gully.fuel.LandCover landCover;
    private final Set<String> fetching = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore gate = new Semaphore(AT_ONCE);

    public OutlookMap(StationRegistry stations, Forecasts forecasts, Droughts droughts, FireDistricts districts, au.gully.cfs.FireBan fireBan, Curing curing,
                      au.gully.fuel.LandCover landCover) {
        this.stations = stations;
        this.forecasts = forecasts;
        this.droughts = droughts;
        this.districts = districts;
        this.fireBan = fireBan;
        this.curing = curing;
        this.landCover = landCover;
    }

    /**
     * Each station's three days, as far as its forecast is held; the ones being fetched counted.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> view(Instant now) {
        Map<String, Object> byStation = new LinkedHashMap<>();
        List<String> dates = new ArrayList<>();
        int held = 0;
        for (Station s : stations.bureau()) {
            Forecast f = forecasts.held(s.id()).orElse(null);
            boolean old = f == null || f.fetchedAt() == null || Duration.between(f.fetchedAt(), now).compareTo(Forecasts.LIFE) >= 0;
            if (old) {
                fetch(s);
            }
            if (f == null) {
                continue;
            }
            held++;
            String district = districts.of(s.lat(), s.lon()).map(fireBan::name).orElse(null);
            au.gully.fuel.LandCover.Cover cover = landCover.at(s.lat(), s.lon()).orElse(null);
            Forecasts.FireInputs in = new Forecasts.FireInputs(droughts.of(s, now).orElse(null), s, district, district == null ? null : curing.of(district).orElse(null),
                    cover == null ? null : cover.fuel());
            Map<String, Object> v = Forecasts.view(f, s, 0.0, now, in);
            List<Map<String, Object>> days = new ArrayList<>();
            for (Map<String, Object> d : (List<Map<String, Object>>) v.get("daily")) {
                if (dates.size() < Forecasts.DAYS && !dates.contains((String) d.get("date"))) {
                    dates.add((String) d.get("date"));
                }
                Map<String, Object> fire = d.get("fire") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("date", d.get("date"));
                day.put("ffdiMax", fire.get("ffdiMax"));
                day.put("ffdiRating", fire.get("ffdiRating"));
                day.put("peakAt", fire.get("peakAt"));
                day.put("gfdiMax", fire.get("gfdiMax"));
                day.put("fbiMax", fire.get("fbiMax"));
                day.put("afdrsRating", fire.get("afdrsRating"));
                day.put("forestFbiMax", fire.get("forestFbiMax"));
                day.put("forestRating", fire.get("forestRating"));
                day.put("pointFbiMax", fire.get("pointFbiMax"));
                day.put("pointRating", fire.get("pointRating"));
                days.add(day);
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("fetchedAt", v.get("fetchedAt"));
            one.put("stale", old);
            one.put("fuel", cover == null ? null : cover.fuel().word);
            one.put("days", days);
            List<?> changes = (List<?>) v.get("windChanges");
            one.put("windChange", changes == null || changes.isEmpty() ? null : changes.getFirst());
            byStation.put(s.id(), one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dates);
        out.put("held", held);
        out.put("pending", fetching.size());
        out.put("stations", byStation);
        return out;
    }

    /**
     * A station's forecast fetched in the background, once at a time per station and {@link #AT_ONCE} at a time in all.
     */
    private void fetch(Station s) {
        if (!fetching.add(s.id())) {
            return;
        }
        pool.submit(() -> {
            try {
                gate.acquire();
                try {
                    forecasts.of(s, Instant.now(), false);
                } finally {
                    gate.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("outlook: {} not fetched: {}", s.id(), e.toString());
            } finally {
                fetching.remove(s.id());
            }
        });
    }
}
