package au.weather.geo;

/**
 * A position in the order people say it, latitude first. The one shape a request body carries a point
 * in; storage is JTS ({@code x = lon}) and the wire is GeoJSON (longitude first), and both flips are
 * written once, in {@link Geo#point} and in the layers' {@code GeoJson}.
 */
public record LatLon(double lat, double lon) {

    public LatLon {
        if (Double.isNaN(lat) || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("latitude must be -90..90, not " + lat);
        }
        if (Double.isNaN(lon) || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("longitude must be -180..180, not " + lon);
        }
    }
}
