package au.gully.science;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The band tables the ratings walk and the vocabulary serves (docs/24 G13). Two things are worth
 * pinning: that every table is a proper scale - contiguous, one open top - and that walking it gives
 * the same words the {@code if} chains gave before, at and either side of every boundary.
 */
class BandTest {

    // The grassland scale is not here. GFDI needs curing and fuel load, which are not weather, so
    // au.hub.core.fuel.GrassFireDanger stayed in the Hub with its metrics manager (docs/09, and the
    // "what stays" list of docs/27 27.4). The "gfdi" entry below and the whole
    // theGrassScaleSharesTheForestWordsButNotItsUpperBoundaries test went with it; everything the
    // forest index asserts is unchanged.
    private static final Map<String, List<Band>> SCALES = Map.of(
            "ffdi", FireDanger.BANDS,
            "kbdi", Kbdi.BANDS,
            "wind", Bands.WIND,
            "humidity", Bands.HUMIDITY,
            "temperature", Bands.TEMPERATURE);

    @Test
    void everyScaleIsContiguousWithOneOpenTop() {
        SCALES.forEach((name, scale) -> {
            assertThat(scale).as(name).isNotEmpty();
            assertThat(scale.getLast().to()).as(name + " ends open").isNull();
            for (int i = 0; i < scale.size(); i++) {
                Band b = scale.get(i);
                if (i == 0) {
                    assertThat(b.from() == null || b.from() == 0.0).as(name + " starts at zero or open").isTrue();
                } else {
                    assertThat(b.from()).as(name + "/" + b.value() + " begins where the band below ends").isEqualTo(scale.get(i - 1).to());
                }
                if (i < scale.size() - 1) {
                    assertThat(b.to()).as(name + "/" + b.value() + " has a ceiling").isNotNull().isGreaterThan(b.from() == null ? -1e9 : b.from());
                }
            }
        });
    }

    /**
     * The cascade is the old {@code if} chain: a value below the first floor and a NaN land where they
     * always did, so swapping the chains for tables changed no answer anywhere.
     */
    @Test
    void theCascadeIsTheIfChainItReplaced() {
        assertThat(Band.of(FireDanger.BANDS, -1)).isEqualTo("LOW-MODERATE");
        assertThat(Band.of(FireDanger.BANDS, Double.NaN)).isEqualTo("CATASTROPHIC");
        assertThat(Band.of(Bands.TEMPERATURE, -3)).as("a frost is mild").isEqualTo("MILD");
        assertThat(Band.of(Kbdi.BANDS, Kbdi.FIELD_CAPACITY_MM)).isEqualTo("SEVERE");
    }

    @Test
    void theWeatherWordsChangeExactlyAtTheirBoundaries() {
        assertThat(Bands.wind(19.9)).isEqualTo("LIGHT");
        assertThat(Bands.wind(20)).isEqualTo("MODERATE");
        assertThat(Bands.wind(39.9)).isEqualTo("MODERATE");
        assertThat(Bands.wind(40)).isEqualTo("STRONG");
        assertThat(Bands.wind(60)).isEqualTo("GALE");

        assertThat(Bands.humidity(14.9)).isEqualTo("VERY_DRY");
        assertThat(Bands.humidity(15)).isEqualTo("DRY");
        assertThat(Bands.humidity(30)).isEqualTo("MODERATE");
        assertThat(Bands.humidity(59.9)).isEqualTo("MODERATE");
        assertThat(Bands.humidity(60)).isEqualTo("HUMID");

        assertThat(Bands.temperature(19.9)).isEqualTo("MILD");
        assertThat(Bands.temperature(20)).isEqualTo("WARM");
        assertThat(Bands.temperature(30)).isEqualTo("HOT");
        assertThat(Bands.temperature(37.9)).isEqualTo("HOT");
        assertThat(Bands.temperature(38)).isEqualTo("EXTREME");
    }

    /**
     * What the forest scale says at the points the grassland one used to be compared against. The
     * comparison itself left with {@code GrassFireDanger}; these three assertions on FFDI are what
     * survives of it, and they are the half this service still serves.
     */
    @Test
    void theForestScaleKeepsItsUpperBoundaries() {
        assertThat(FireDanger.rating(50)).isEqualTo("SEVERE");
        assertThat(FireDanger.rating(75)).isEqualTo("EXTREME");
        assertThat(FireDanger.rating(100)).isEqualTo("CATASTROPHIC");
    }
}
