package au.gully.platform;

import au.gully.bureau.StationRegistry;
import au.gully.hexagons.Hexagon;
import au.gully.hexagons.HexagonStore;
import au.gully.upstreams.Upstreams;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Spend, breaker state and freshness as metrics (docs/06 item 11), on the Prometheus endpoint the
 * actuator already exposes: {@code gully_upstream_spent_units{upstream,window}},
 * {@code gully_upstream_breaker_open{upstream}}, {@code gully_hexagons{state}},
 * {@code gully_stale_hexagons}, {@code gully_stations}, {@code gully_station_age_seconds}.
 */
@Component
public class Metrics {

    public Metrics(MeterRegistry registry, Upstreams upstreams, HexagonStore store, StationRegistry stations) {
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
        Gauge.builder("gully.hexagons", () -> store.all().size()).tags(Tags.of("state", "held")).register(registry);
        Gauge.builder("gully.hexagons", () -> store.all().stream().filter(Hexagon::active).count()).tags(Tags.of("state", "active")).register(registry);
        Gauge.builder("gully.hexagons", () -> store.all().stream().filter(Hexagon::hasForecast).count()).tags(Tags.of("state", "forecast")).register(registry);
        Gauge.builder("gully.stale.hexagons", () -> {
            Instant now = Instant.now();
            return store.all().stream().filter(h -> h.hasForecast() && !h.hasStation() && store.life().expired(h.forecast(), now)).count();
        }).register(registry);
        Gauge.builder("gully.stations", stations::size).register(registry);
        Gauge.builder("gully.station.age.seconds", () -> stations.lastUpdateAt() == null ? -1
                : Duration.between(stations.lastUpdateAt(), Instant.now()).toSeconds()).register(registry);
        Gauge.builder("gully.served", store::servedCount).register(registry);
        Gauge.builder("gully.fetched", store::fetchedCount).register(registry);
    }
}
