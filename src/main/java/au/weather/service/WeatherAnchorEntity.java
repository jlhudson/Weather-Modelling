package au.weather.service;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * One cached reading, at one point. The unit of everything this feature does to avoid calling an
 * upstream: an anchor is created by the first request in an area and reused by every request that
 * lands within the configured radius before it expires (docs/09 9.2).
 * <p>
 * Persisted rather than held only in memory, because a restart that discards the cache spends the
 * free allowance again for readings it already had - and restarts are frequent while a system is
 * being built.
 *
 */
@Entity
@Table(name = "weather_anchor", indexes = {
        @Index(name = "ix_weather_anchor_expires", columnList = "expires_at"),
        @Index(name = "ix_weather_anchor_point", columnList = "lat, lon")
})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@Setter
@NoArgsConstructor
public class WeatherAnchorEntity {

    @EqualsAndHashCode.Include
    @Id
    private UUID id;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(length = 64)
    private String model;

    /**
     * The whole normalised {@code WeatherReport}, so a restart rehydrates without re-parsing anything upstream.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * True ground height under the anchor, sampled from the terrain tiles once when it was stored.
     * <p>
     * <strong>Not the same thing as {@code WeatherReport.elevationM}</strong>, which is the height of the
     * model grid cell the provider answered from - a smoothed figure that can sit hundreds of metres from
     * the ground it claims to describe. Both are kept: the model's height explains the reading, this one
     * places it. Comparing a query point against the model's height would measure the model's smoothing
     * rather than the terrain between two points, which is the opposite of the intent.
     * <p>
     * Nullable, and it has to be. Terrain can be switched off entirely, and a tile can be missing over
     * the sea - and {@code null} must never collapse into {@code 0.0}, because sea level is a legitimate
     * answer that a great deal of South Australia genuinely gives.
     */
    @Column(name = "terrain_m")
    private Double terrainM;

    @Column(nullable = false)
    private int hits;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;
}
