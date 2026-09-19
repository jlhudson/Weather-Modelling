package au.gully.bureau;

import java.util.ArrayList;
import java.util.List;

/**
 * Which states' files a point could be in: the Bureau publishes one station file and one warnings
 * feed per state, and a hexagon's stations and its neighbours' are in the file of the state it is
 * in — or, near a border, in either. The borders are boxes with a degree of margin each way, which
 * is coarser than the real lines and deliberately so: near a border both files are read, which
 * costs one conditional GET, and never neither.
 */
public final class States {

    private static final double MARGIN = 1.0;

    private States() {
    }

    /**
     * The state codes whose file could hold stations within a hexagon or two of the point, in the
     * Bureau's own lower-case codes; at least one, up to three in a corner.
     */
    public static List<String> covering(double lat, double lon) {
        List<String> out = new ArrayList<>();
        // Tasmania and Victoria: south of the mainland, and south of the Murray east of 141°.
        if (lat < -39.0 + MARGIN && lon > 143.0 - MARGIN && lon < 149.0 + MARGIN) {
            out.add("tas");
        }
        if (lat < -33.9 + MARGIN && lat > -39.5 - MARGIN && lon > 140.9 - MARGIN) {
            out.add("vic");
        }
        // New South Wales (with the ACT): east of 141°, between 29° S and the Murray.
        if (lat < -28.0 + MARGIN && lat > -37.6 - MARGIN && lon > 140.9 - MARGIN) {
            out.add("nsw");
        }
        // Queensland: east of 138°, north of 29° S.
        if (lat > -29.2 - MARGIN && lon > 137.9 - MARGIN) {
            out.add("qld");
        }
        // South Australia: 129° to 141°, south of 26° S.
        if (lat < -25.9 + MARGIN && lon > 128.9 - MARGIN && lon < 141.1 + MARGIN) {
            out.add("sa");
        }
        // The Northern Territory: 129° to 138°, north of 26° S.
        if (lat > -26.1 - MARGIN && lon > 128.9 - MARGIN && lon < 138.1 + MARGIN) {
            out.add("nt");
        }
        // Western Australia: west of 129°.
        if (lon < 129.1 + MARGIN) {
            out.add("wa");
        }
        if (out.isEmpty()) {
            out.add("sa");
        }
        return out;
    }
}
