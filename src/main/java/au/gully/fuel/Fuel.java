package au.gully.fuel;

/**
 * The fuel at a place (W-38), from its land cover: which AFDRS fire behaviour model is its rating. Pure, no state.
 * <p>
 * The land cover is Digital Earth Australia's (the FAO LCCS levels 3 and 4). AFDRS rates dry forest and temperate
 * woodland - natural woody cover of 15 % or more - with its forest model, and grassland, grassy woodland (woody cover
 * under 15 %), crops and pasture with its grassland model; water and built-up land burn in no model. Wetlands, woody
 * horticulture (orchards, vineyards) and arid shrubland (chenopod, spinifex) have AFDRS models of their own that this
 * service does not compute, and are said to be so rather than given another fuel's figure.
 */
public final class Fuel {

    private Fuel() {
    }

    public enum Kind {
        FOREST("forest", "AFDRS dry forest"),
        GRASS("grass", "AFDRS grassland (CSIRO)"),
        UNBURNABLE("unburnable", null),
        NOT_MODELLED("not modelled", null),
        UNKNOWN("unknown", null);

        public final String word, model;

        Kind(String word, String model) {
            this.word = word;
            this.model = model;
        }
    }

    /**
     * The fuel for a land cover class: level 3 the broad class, level 4's label the detail DEA gives it.
     */
    public static Kind of(Integer level3, String level4Label) {
        if (level3 == null) {
            return Kind.UNKNOWN;
        }
        String l = level4Label == null ? "" : level4Label.toLowerCase();
        return switch (level3) {
            case 215, 220 -> Kind.UNBURNABLE;
            case 124 -> Kind.NOT_MODELLED;
            case 216 -> Kind.NOT_MODELLED;
            case 111 -> l.contains("woody") ? Kind.NOT_MODELLED : Kind.GRASS;
            case 112 -> l.contains("woody") ? (woodyCover(l) >= 15 ? Kind.FOREST : Kind.GRASS) : Kind.GRASS;
            default -> Kind.UNKNOWN;
        };
    }

    /**
     * Why a fuel is what it is, in words.
     */
    public static String why(Integer level3, String level4Label) {
        return switch (of(level3, level4Label)) {
            case FOREST -> "natural woody cover of 15 % or more: forest or woodland";
            case GRASS -> level3 != null && level3 == 111 ? "cultivated herbaceous: crop or pasture" : level4Label != null && level4Label.toLowerCase().contains("woody")
                    ? "woody cover under 15 %: grassy woodland" : "herbaceous: grassland";
            case UNBURNABLE -> level3 != null && level3 == 220 ? "water" : "built-up";
            case NOT_MODELLED -> level3 == null ? "" : switch (level3) {
                case 124 -> "wetland: the AFDRS rates it with a wetland model not computed here";
                case 111 -> "woody horticulture: the AFDRS rates it with a model not computed here";
                default -> "sparse or bare ground: arid shrubland the AFDRS rates with chenopod or spinifex models not computed here";
            };
            case UNKNOWN -> "no land cover known";
        };
    }

    /**
     * The woody cover a level 4 label gives, per cent: the lower bound of its band ("Open (15 to 40 %)" is 15, "Closed
     * (> 65 %)" 65); 0 where it gives none.
     */
    static int woodyCover(String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(\\s*>?\\s*(\\d+)").matcher(label);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
