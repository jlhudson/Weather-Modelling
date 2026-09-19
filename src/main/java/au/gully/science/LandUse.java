package au.gully.science;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a hexagon is made of, as a percentage per class, and the class at the point that was asked
 * about (docs/06 item 19). Counted once from the mounted land-cover raster when the hexagon is first
 * created, never on request.
 * <p>
 * It matters twice. It is worth returning for the point itself — "this point is in pine forest",
 * "this is a car park" — and it decides which fire index is the right one: a hexagon that is mostly
 * forest leads with the McArthur forest index, mostly grass leads with the grassland indices, and one
 * that is mostly water or built-up carries both but says they apply to little of it.
 *
 * @param pointClass the class of the cell the point itself falls in, or null when no file is mounted
 * @param percent    whole-number share of the hexagon per class; absent classes are absent, not zero
 */
public record LandUse(LandClass pointClass, Map<LandClass, Integer> percent) {

    /**
     * The seven classes every land-cover product can be reduced to, and the one for a value the
     * mapping does not name. Coarse on purpose: two fire indices only need to know grass from trees.
     */
    public enum LandClass {
        FOREST, GRASSLAND, CROPLAND, SCRUB, BUILT_UP, WATER, BARE, UNKNOWN;

        /** JSON and the console use the lower-case word. */
        public String key() {
            return name().toLowerCase();
        }
    }

    public LandUse {
        Map<LandClass, Integer> copy = new EnumMap<>(LandClass.class);
        if (percent != null) {
            percent.forEach((k, v) -> {
                if (k != null && v != null && v > 0) {
                    copy.put(k, v);
                }
            });
        }
        percent = Map.copyOf(copy);
    }

    public int share(LandClass c) {
        return percent.getOrDefault(c, 0);
    }

    /**
     * Which index leads: {@code forest} where trees and scrub outweigh grass and crop, {@code grass}
     * the other way round, and null where the hexagon holds no fuel to speak of.
     */
    public String leads() {
        int woody = share(LandClass.FOREST) + share(LandClass.SCRUB);
        int grassy = share(LandClass.GRASSLAND) + share(LandClass.CROPLAND);
        if (woody + grassy == 0) {
            return null;
        }
        return woody > grassy ? "forest" : "grass";
    }

    /**
     * How much of the hexagon the fire indices describe at all: the burnable share.
     */
    public int burnablePct() {
        return share(LandClass.FOREST) + share(LandClass.SCRUB) + share(LandClass.GRASSLAND) + share(LandClass.CROPLAND);
    }

    /**
     * The percentages keyed by the class word, in class order, for JSON.
     */
    public Map<String, Integer> byKey() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (LandClass c : LandClass.values()) {
            Integer v = percent.get(c);
            if (v != null) {
                out.put(c.key(), v);
            }
        }
        return out;
    }
}
