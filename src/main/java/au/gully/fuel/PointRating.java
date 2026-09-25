package au.gully.fuel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AFDRS rating of a place (W-38): the fire behaviour index of the fuel its land cover says it carries - the forest
 * model's where forest grows, the grassland model's where grass does - and nothing guessed where the fuel is one this
 * service does not model or does not burn. Computed here from the AFDRS's own models; the CFS's published rating for
 * the district stands beside it in {@code fireBan}.
 */
public final class PointRating {

    private PointRating() {
    }

    /**
     * The block: the fuel and why, the land cover it rests on, and the index and rating of that fuel's model.
     *
     * @param forest the forest block of the fire picture, or null
     * @param grass  the grass block, or null
     */
    public static Map<String, Object> block(LandCover.Cover cover, Map<String, Object> forest, Map<String, Object> grass) {
        Map<String, Object> m = new LinkedHashMap<>();
        Fuel.Kind kind = cover == null ? Fuel.Kind.UNKNOWN : cover.fuel();
        Map<String, Object> fuel = fuel(cover);
        m.put("fuel", fuel);
        m.put("model", kind.model);
        Object fbi = null, rating = null;
        String note = null;
        switch (kind) {
            case FOREST -> {
                fbi = forest == null ? null : forest.get("fbi");
                rating = forest == null ? null : forest.get("rating");
                note = forest == null ? "the forest index needs a drought factor" : "the forest fuel is provisional: the AFDRS's own default table is not published openly";
            }
            case GRASS -> {
                fbi = grass == null ? null : grass.get("fbi");
                rating = grass == null ? null : grass.get("afdrsRating");
                note = grass == null ? "no fire ban district here, so no curing" : fbi == null ? (String) grass.get("note") : null;
            }
            default -> note = (String) fuel.get("why");
        }
        m.put("fbi", fbi);
        m.put("rating", rating);
        m.put("note", note);
        return m;
    }

    /**
     * The fuel of a place and why, and the land cover it rests on.
     */
    public static Map<String, Object> fuel(LandCover.Cover cover) {
        Fuel.Kind kind = cover == null ? Fuel.Kind.UNKNOWN : cover.fuel();
        Map<String, Object> fuel = new LinkedHashMap<>();
        fuel.put("type", kind.word);
        fuel.put("why", cover == null ? "the land cover could not be read" : Fuel.why(cover.level3(), cover.label()));
        fuel.put("landCover", cover == null ? null : cover.label());
        fuel.put("year", cover == null ? null : cover.year());
        fuel.put("source", "Digital Earth Australia land cover (Landsat, Collection 3)");
        return fuel;
    }

    /**
     * Which of a forecast day's worst hours is the place's: the forest's or the grass's, by its fuel.
     */
    public static Object[] day(Fuel.Kind kind, Map<String, Object> dayFire) {
        if (dayFire == null || kind == null) {
            return new Object[]{null, null};
        }
        return switch (kind) {
            case FOREST -> new Object[]{dayFire.get("forestFbiMax"), dayFire.get("forestRating")};
            case GRASS -> new Object[]{dayFire.get("fbiMax"), dayFire.get("afdrsRating")};
            default -> new Object[]{null, null};
        };
    }
}
