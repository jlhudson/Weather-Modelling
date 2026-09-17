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
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cached river discharge series for a point, valid for a day.
 * <p>
 * Cached on a much tighter radius than the drought cell, because discharge is a property of a
 * particular watercourse rather than of the atmosphere. GloFAS answers for the largest river within
 * about 5 km, so reusing one cell across fifty kilometres would confidently report a different river.
 *
 */
@Entity
@Table(name = "river_cell", indexes = {
        @Index(name = "ix_river_cell_day", columnList = "computed_for"),
        @Index(name = "ix_river_cell_point", columnList = "lat, lon")
})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@Setter
@NoArgsConstructor
public class RiverCellEntity {

    @EqualsAndHashCode.Include
    @Id
    private UUID id;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String discharge;

    /**
     * False when GloFAS models no river near this point, which is an answer rather than a failure.
     */
    @Column(name = "has_river", nullable = false)
    private boolean hasRiver;

    @Column(name = "computed_for", nullable = false)
    private LocalDate computedFor;

    /**
     * True ground height under the cell, for the three-dimensional match. Nullable: terrain can be off, or
     * a tile can be missing. A null is compared horizontally rather than excluded.
     */
    @Column(name = "terrain_m")
    private Double terrainM;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
