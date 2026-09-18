package au.gully.bureau;

/**
 * One of the Bureau's automatic weather stations, exactly as the state file describes it — never a
 * hand-typed list, so a new or moved station appears on its own (docs/06 item 12).
 *
 * @param id       the Bureau's station number ({@code bom-id}), the key everything is held under
 * @param wmoId    the WMO number, which the Bureau's own JSON products are addressed by
 * @param name     as published, e.g. {@code ADELAIDE (WEST TERRACE / NGAYIRDAPIRA)}
 * @param heightM  the station's own height above sea level
 * @param zone     the station's time zone id
 * @param district the Bureau public weather district the station sits in ({@code SA_PW001}), which
 *                 is what the warnings are issued against
 * @param state    the state whose file it came from, lower case ({@code sa})
 */
public record Station(String id, String wmoId, String name, double lat, double lon, Double heightM,
                      String zone, String district, String state) {
}
