package au.gully.hexagons;

import au.gully.science.LandUse;
import au.gully.upstreams.Forecast;

import java.time.Instant;

/**
 * Everything the service holds about one hexagon, as one immutable value replaced whole whenever any
 * of it changes (docs/06 items 0 and 14): answering a request is one map lookup and no database.
 * <p>
 * A hexagon exists once something inside it has been asked about, or because a Bureau station sits
 * in it. What it is made of — elevation, slope, land use, its districts, its station — is worked out
 * once when it is created. What it holds — the forecast, the drought state, the river, the fire
 * picture — is replaced as each refreshes.
 *
 * @param zone           the hexagon's time zone: the upstream's answer, else the station's, else the default
 * @param elevationFrom  {@code terrain} for the mounted file, {@code model} for the upstream's cell, {@code station} for the station's own height
 * @param fireBanDistrict the CFS fire ban district the centre falls in, or null outside South Australia
 * @param bureauDistrict  the Bureau public weather district, from the nearest station
 * @param stationId      a Bureau station inside the hexagon, or null; such a hexagon is always alive
 * @param nearestStationId the nearest station, inside or not, whose values ride on every reading
 * @param activatedAt    when The Hub (or anyone) first asked about it; null on a station-only hexagon
 * @param lastAskedAt    the last ask, which is what keeps it warm
 * @param lastSnapshotAt when history was last written for it
 * @param fire           the computed picture, or null until there is enough to compute one
 * @param version        bumped on every replacement, for the map layer's fingerprint
 */
public record Hexagon(
        Cell cell,
        String zone,
        Double elevationM,
        String elevationFrom,
        Double slopeDeg,
        LandUse landUse,
        String fireBanDistrict,
        String bureauDistrict,
        String stationId,
        String nearestStationId,
        Double nearestStationKm,
        Forecast forecast,
        DroughtState drought,
        RiverState river,
        FirePicture fire,
        Instant createdAt,
        Instant activatedAt,
        Instant lastAskedAt,
        Instant lastSnapshotAt,
        long asks,
        long version
) {

    public String id() {
        return cell.id();
    }

    /**
     * Active means someone has asked (docs/06 item 16); a hexagon that only has a station in it is
     * not active until then.
     */
    public boolean active() {
        return activatedAt != null;
    }

    public boolean hasStation() {
        return stationId != null;
    }

    public boolean hasForecast() {
        return forecast != null;
    }

    /**
     * What the map shows a hexagon as: a station in it, a forecast loaded, both.
     */
    public String kind() {
        if (hasStation() && hasForecast()) {
            return "both";
        }
        return hasStation() ? "station" : hasForecast() ? "forecast" : "bare";
    }

    public Hexagon withForecast(Forecast f, Instant now) {
        return new Hexagon(cell, f != null && f.zoneId() != null ? f.zoneId() : zone,
                elevationFrom == null || "model".equals(elevationFrom) ? (f == null || f.modelElevationM() == null ? elevationM : f.modelElevationM()) : elevationM,
                elevationFrom == null && f != null && f.modelElevationM() != null ? "model" : elevationFrom,
                slopeDeg, landUse, fireBanDistrict, bureauDistrict, stationId, nearestStationId, nearestStationKm,
                f, drought, river, fire, createdAt, activatedAt, lastAskedAt, lastSnapshotAt, asks, version + 1);
    }

    public Hexagon withDrought(DroughtState d) {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBanDistrict, bureauDistrict,
                stationId, nearestStationId, nearestStationKm, forecast, d, river, fire, createdAt, activatedAt,
                lastAskedAt, lastSnapshotAt, asks, version + 1);
    }

    public Hexagon withRiver(RiverState r) {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBanDistrict, bureauDistrict,
                stationId, nearestStationId, nearestStationKm, forecast, drought, r, fire, createdAt, activatedAt,
                lastAskedAt, lastSnapshotAt, asks, version + 1);
    }

    public Hexagon withFire(FirePicture p) {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBanDistrict, bureauDistrict,
                stationId, nearestStationId, nearestStationKm, forecast, drought, river, p, createdAt, activatedAt,
                lastAskedAt, lastSnapshotAt, asks, version + 1);
    }

    public Hexagon asked(Instant now) {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBanDistrict, bureauDistrict,
                stationId, nearestStationId, nearestStationKm, forecast, drought, river, fire, createdAt,
                activatedAt == null ? now : activatedAt, now, lastSnapshotAt, asks + 1, version);
    }

    public Hexagon snapshotted(Instant now) {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBanDistrict, bureauDistrict,
                stationId, nearestStationId, nearestStationKm, forecast, drought, river, fire, createdAt, activatedAt,
                lastAskedAt, now, asks, version);
    }

    public Hexagon withDistrict(String fireBan) {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBan, bureauDistrict,
                stationId, nearestStationId, nearestStationKm, forecast, drought, river, fire, createdAt, activatedAt,
                lastAskedAt, lastSnapshotAt, asks, version + 1);
    }

    public Hexagon withStations(String stationInside, String nearest, Double nearestKm, String district) {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBanDistrict,
                district == null ? bureauDistrict : district, stationInside, nearest, nearestKm, forecast, drought,
                river, fire, createdAt, activatedAt, lastAskedAt, lastSnapshotAt, asks, version + 1);
    }

    /**
     * The forecast dropped, because nobody has asked for long enough. The rest stays.
     */
    public Hexagon cold() {
        return new Hexagon(cell, zone, elevationM, elevationFrom, slopeDeg, landUse, fireBanDistrict, bureauDistrict,
                stationId, nearestStationId, nearestStationKm, null, drought, river, null, createdAt, activatedAt,
                lastAskedAt, lastSnapshotAt, asks, version + 1);
    }
}
