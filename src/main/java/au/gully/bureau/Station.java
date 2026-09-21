package au.gully.bureau;

/**
 * One station: one of the Bureau's automatic weather stations, exactly as the state file describes
 * it — never a hand-typed list, so a new or moved station appears on its own — or a point of our own
 * (W-7), dropped where nobody's reach contained an ask, with the same terrain, reach and record and
 * its current from the model.
 *
 * @param id       the Bureau's station number ({@code bom-id}), or {@code p-} and a hash for a point
 * @param wmoId    the WMO number, which the Bureau's own JSON products are addressed by; null for a point
 * @param name     as published, e.g. {@code ADELAIDE (WEST TERRACE / NGAYIRDAPIRA)}
 * @param heightM  the station's own height above sea level
 * @param zone     the station's time zone id
 * @param district the Bureau public weather district the station sits in ({@code SA_PW001}); null for a point
 * @param state    the state whose file it came from, lower case ({@code sa})
 * @param kind     {@link #BUREAU} or {@link #POINT}
 */
public record Station(String id, String wmoId, String name, double lat, double lon, Double heightM,
                      String zone, String district, String state, String kind) {

    public static final String BUREAU = "bureau";
    public static final String POINT = "point";

    /**
     * A Bureau station.
     */
    public Station(String id, String wmoId, String name, double lat, double lon, Double heightM, String zone, String district, String state) {
        this(id, wmoId, name, lat, lon, heightM, zone, district, state, BUREAU);
    }

    public boolean isPoint() {
        return POINT.equals(kind);
    }
}
