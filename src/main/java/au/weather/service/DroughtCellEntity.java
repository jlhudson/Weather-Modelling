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
 * A spun-up soil moisture deficit for an area, valid for a day.
 * <p>
 * Persisted for a different reason from the weather anchor. An anchor is saved so a restart does not
 * re-spend a call; this is saved because re-deriving it costs <em>a year of daily data</em>, which is
 * several allowance units and a second of wall clock. Losing it on every deploy would make the drought
 * factor the most expensive number in the system.
 *
 */
@Entity
@Table(name = "drought_cell", indexes = {
        @Index(name = "ix_drought_cell_day", columnList = "computed_for"),
        @Index(name = "ix_drought_cell_point", columnList = "lat, lon")
})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@Setter
@NoArgsConstructor
public class DroughtCellEntity {

    @EqualsAndHashCode.Include
    @Id
    private UUID id;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @Column(name = "kbdi_mm", nullable = false)
    private double kbdiMm;

    @Column(name = "kbdi_band", length = 16)
    private String kbdiBand;

    @Column(name = "drought_factor", nullable = false)
    private double droughtFactor;

    @Column(name = "mean_annual_rainfall_mm", nullable = false)
    private double meanAnnualRainfallMm;

    @Column(name = "spun_up_from", nullable = false)
    private LocalDate spunUpFrom;

    @Column(name = "computed_for", nullable = false)
    private LocalDate computedFor;

    @Column(name = "spin_up_days", nullable = false)
    private int spinUpDays;

    @Column(nullable = false)
    private boolean complete;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rain_history", columnDefinition = "jsonb")
    private String rainHistory;

    /**
     * True ground height under the cell, for the three-dimensional match. Nullable: terrain can be off, or
     * a tile can be missing. A null is compared horizontally rather than excluded.
     */
    @Column(name = "terrain_m")
    private Double terrainM;

    @Column(length = 256)
    private String basis;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
