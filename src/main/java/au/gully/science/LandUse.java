package au.gully.science;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a hexagon is made of, as a percentage per class (docs/06 item 19, W-15). Counted once per
 * hexagon from a land-cover raster: the mounted file when one is there, else Digital Earth
 * Australia's land cover read for the hexagon on its first ask and kept with it.
 * <p>
 * It matters twice. The class at the point asked about is worth returning on its own — "this point
 * is in pine forest", "this is a car park" — and the shares decide which fire index is the right one:
 * a hexagon that is mostly forest leads with the McArthur forest index, mostly grass leads with the
 * grassland indices, and one that is mostly water or built-up carries both but says they apply to
 * little of it.
 *
 * @param percent whole-number share of the hexagon per class; absent classes are absent, not zero
 * @param source  where the raster came from: the mounted file's name, or {@code dea-landcover-2025}
 */
public record LandUse(Map<LandClass, Integer> percent, String source) {

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
     * The class with the largest share, for a map's one colour; null when nothing was counted.
     */
    public LandClass dominant() {
        LandClass best = null;
        int most = 0;
        for (LandClass c : LandClass.values()) {
            int v = share(c);
            if (v > most) {
                most = v;
                best = c;
            }
        }
        return best;
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
