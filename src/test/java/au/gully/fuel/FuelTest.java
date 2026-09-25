package au.gully.fuel;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FuelTest {

    @Test
    void theLandCoverSaysWhoseIndexIsThePlacesRating() {
        // DEA Collection 3 classes as its map service gave them, September 2026.
        assertThat(Fuel.of(112, "Natural Terrestrial Vegetated: Woody Open (40 to 65 %)")).as("the Adelaide Hills").isEqualTo(Fuel.Kind.FOREST);
        assertThat(Fuel.of(112, "Natural Terrestrial Vegetated: Woody Closed (> 65 %)")).isEqualTo(Fuel.Kind.FOREST);
        assertThat(Fuel.of(112, "Natural Terrestrial Vegetated: Woody Open (15 to 40 %)")).as("woodland").isEqualTo(Fuel.Kind.FOREST);
        assertThat(Fuel.of(112, "Natural Terrestrial Vegetated: Woody Sparse (4 to 15 %)")).as("grassy woodland").isEqualTo(Fuel.Kind.GRASS);
        assertThat(Fuel.of(112, "Natural Terrestrial Vegetated: Herbaceous Sparse (4 to 15 %)")).as("north of Gawler").isEqualTo(Fuel.Kind.GRASS);
        assertThat(Fuel.of(111, "Cultivated Terrestrial Vegetated: Herbaceous")).as("crop or pasture").isEqualTo(Fuel.Kind.GRASS);
        assertThat(Fuel.of(111, "Cultivated Terrestrial Vegetated: Woody")).as("a vineyard").isEqualTo(Fuel.Kind.NOT_MODELLED);
        assertThat(Fuel.of(215, "Artificial Surface")).as("the city").isEqualTo(Fuel.Kind.UNBURNABLE);
        assertThat(Fuel.of(220, "Water: Perennial (> 9 months)")).as("the gulf").isEqualTo(Fuel.Kind.UNBURNABLE);
        assertThat(Fuel.of(216, "Natural Surface: Sparsely vegetated")).as("Coober Pedy").isEqualTo(Fuel.Kind.NOT_MODELLED);
        assertThat(Fuel.of(124, "Natural Aquatic Vegetated")).isEqualTo(Fuel.Kind.NOT_MODELLED);
        assertThat(Fuel.of(null, null)).isEqualTo(Fuel.Kind.UNKNOWN);
    }

    @Test
    void theNewestYearIsReadOffTheMapService() {
        String answer = "{\"type\": \"FeatureCollection\", \"features\": [{\"type\": \"Feature\", \"properties\": {\"data\": ["
                + "{\"time\": \"2025-01-01\", \"bands\": {\"level4\": 28, \"level3\": 112}, \"description\": {\"level3_label\": \"Natural Terrestrial Vegetation\","
                + " \"level4_label\": \"Natural Terrestrial Vegetated: Woody Open (40 to 65 %)\"}}]}}]}";
        LandCover.Cover c = LandCover.parse(JsonMapper.builder().build().readTree(answer));
        assertThat(c).isEqualTo(new LandCover.Cover(112, 28, "Natural Terrestrial Vegetated: Woody Open (40 to 65 %)", 2025));
        assertThat(c.fuel()).isEqualTo(Fuel.Kind.FOREST);
    }

    @Test
    void thePlacesRatingIsItsOwnFuelsIndexAndNothingGuessed() {
        LandCover.Cover hills = new LandCover.Cover(112, 28, "Natural Terrestrial Vegetated: Woody Open (40 to 65 %)", 2025);
        Map<String, Object> forest = Map.of("fbi", 30, "rating", "High"), grass = Map.of("fbi", 55, "afdrsRating", "Extreme");
        assertThat(PointRating.block(hills, forest, grass)).containsEntry("fbi", 30).containsEntry("rating", "High").containsEntry("model", "AFDRS dry forest");
        LandCover.Cover paddock = new LandCover.Cover(111, 33, "Cultivated Terrestrial Vegetated: Herbaceous", 2025);
        assertThat(PointRating.block(paddock, forest, grass)).containsEntry("fbi", 55).containsEntry("rating", "Extreme");
        // Grass without a curing figure: no index, and the grass block's reason.
        Map<String, Object> noCuring = Map.of("note", "no curing figure has been entered for Mid North; the grass indices need one");
        assertThat(PointRating.block(paddock, forest, noCuring)).containsEntry("fbi", null).extractingByKey("note").asString().contains("no curing");
        LandCover.Cover city = new LandCover.Cover(215, 93, "Artificial Surface", 2025);
        assertThat(PointRating.block(city, forest, grass)).containsEntry("fbi", null).containsEntry("rating", null).containsEntry("note", "built-up");
        assertThat(PointRating.day(Fuel.Kind.FOREST, Map.of("forestFbiMax", 40, "forestRating", "High", "fbiMax", 70))).containsExactly(40, "High");
    }

    @Test
    void theWoodyCoverIsTheLowerBoundOfItsBand() {
        assertThat(Fuel.woodyCover("woody open (15 to 40 %)")).isEqualTo(15);
        assertThat(Fuel.woodyCover("woody closed (> 65 %)")).isEqualTo(65);
        assertThat(Fuel.woodyCover("woody")).isZero();
    }
}
