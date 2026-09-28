package au.gully.platform;

import au.gully.bureau.StationRegistry;
import au.gully.upstreams.Upstreams;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Spend, breaker state and the stations' freshness as Micrometer meters:
 * {@code gully_upstream_spent_units{upstream,window}}, {@code gully_upstream_breaker_open{upstream}},
 * {@code gully_stations}, {@code gully_station_age_seconds}. The actuator exposes health alone (Uptime
 * Kuma reads it); these are in the registry for the day a metrics endpoint is switched on.
 */
@Component
public class Metrics {

    public Metrics(MeterRegistry registry, Upstreams upstreams, StationRegistry stations) {
        for (String id : upstreams.order()) {
            for (String window : new String[]{"day", "month"}) {
                Gauge.builder("gully.upstream.spent.units", () -> upstreams.status().stream()
                                .filter(s -> s.id().equals(id)).findFirst()
                                .map(s -> s.spent().getOrDefault(window, 0.0)).orElse(0.0))
                        .tags(Tags.of("upstream", id, "window", window)).register(registry);
            }
            Gauge.builder("gully.upstream.breaker.open", () -> upstreams.breaker().check(id).allowed() ? 0 : 1)
                    .tags(Tags.of("upstream", id)).register(registry);
        }
        Gauge.builder("gully.stations", stations::size).register(registry);
        Gauge.builder("gully.station.age.seconds", () -> stations.lastUpdateAt() == null ? -1
                : Duration.between(stations.lastUpdateAt(), Instant.now()).toSeconds()).register(registry);
    }
}
