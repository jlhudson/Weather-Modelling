package au.gully.api;

import au.gully.science.Conditions;
import au.gully.science.DroughtIndex;
import au.gully.science.FloodOutlook;
import au.gully.science.FloodWeather;
import au.gully.science.WindChange;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract (docs/06 item 9): what {@link Reading} serialises to matches
 * {@code contract/reading.schema.json}, both with every block present and with none, and the copy
 * of the schema The Hub keeps is byte-for-byte this one when the two repositories sit side by side.
 * <p>
 * The schema forbids unknown properties at every level, so a field added or renamed on either side
 * fails this test until the schema — and the other repository's copy — say so too.
 */
class ReadingContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Schema schema() throws IOException {
        try (InputStream in = ReadingContractTest.class.getResourceAsStream("/contract/reading.schema.json")) {
            assertThat(in).isNotNull();
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        }
    }

    private static void assertMatches(Object reading) throws IOException {
        String json = MAPPER.writeValueAsString(reading);
        JsonNode node = MAPPER.readTree(json);
        List<Error> errors = schema().validate(node);
        assertThat(errors).as(json).isEmpty();
    }

    static Reading full() {
        Instant at = Instant.parse("2026-09-18T13:50:00Z");
        Conditions current = Conditions.at(at).temperature(15.0).apparent(11.4).dewPoint(3.2).humidity(45).wind(11.0)
                .windDirection(42).gust(17.0).precipitation(0.0).pressure(1025.8).visibility(71_000.0).condition("Clear").build();
        WindChange change = new WindChange(Instant.parse("2026-09-19T05:00:00Z"), 315, 225, 32.0, 55.0);
        Reading.GrassBlock grass = new Reading.GrassBlock(80, LocalDate.of(2026, 9, 14), 4.5, "grazed", 9.1, "LOW-MODERATE", 1.18, 8.3, 2.1, 4_900L, 1.2, 8, "No Rating");
        Reading.OfficialBlock official = new Reading.OfficialBlock("Mount Lofty Ranges", "No Rating", 0, false, LocalDate.of(2026, 5, 1),
                Instant.parse("2026-04-30T14:30:00Z"), Instant.parse("2026-05-01T14:30:00Z"),
                List.of(new Reading.OfficialDay(0, LocalDate.of(2026, 5, 1), "No Rating", 0, false, Instant.parse("2026-04-30T14:30:00Z"), Instant.parse("2026-05-01T14:30:00Z"))),
                Instant.parse("2026-09-18T13:00:00Z"));
        Reading.FireBlock fire = new Reading.FireBlock(6.3, "LOW-MODERATE", 14.0, 6.8, 88.0, "DRYING", "grass", 92, grass, official,
                new Reading.WindBlock(11.0, 42, 17.0, "LIGHT", change), false, 0.25, 0.21, 0.28, 900.0, 31.0, 312, 40.0, 3.0);
        FloodWeather flood = new FloodWeather(0.0, 0.0, 2.4, 11.2, 0.0, 0.2, 0.4, 2.8, 2.8, 60, 0.21, 0.28, 12.0, 20.0, 0.6, "STEADY",
                List.of(new FloodOutlook(LocalDate.of(2026, 9, 19), 2.4, 60, 12.0, 0.6)));
        DroughtIndex drought = new DroughtIndex(88.0, "DRYING", 6.8, 612.0, LocalDate.of(2025, 9, 18), LocalDate.of(2026, 9, 17), 365,
                List.of(0.0, 0.0, 2.4, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        Reading.Day day = new Reading.Day(LocalDate.of(2026, 9, 19), 19.0, 9.2, 17.0, 60, 31.0, 55.0, 240, 2.4, 60, 4.1,
                Instant.parse("2026-09-18T20:40:00Z"), Instant.parse("2026-09-19T08:40:00Z"), "Light rain",
                new Reading.DayFire(8.0, "LOW-MODERATE", 7.5, "LOW-MODERATE", 6, "No Rating", 87.0, 6.6, 19.0, 60, 31.0, 55.0, 2.4),
                new Reading.DayFlood(2.4, 60, 12.0, 0.6));
        Reading.Hour hour = new Reading.Hour(Instant.parse("2026-09-18T14:00:00Z"), 10.2, 83, 10.1, 205, 18.0, 0.1, 20, "Overcast",
                new Reading.HourFire(3.1, "LOW-MODERATE", 2.0, "LOW-MODERATE", 1, "No Rating", 6.8));
        return new Reading(Reading.SCHEMA, true, null, new Reading.Point(-34.93, 138.6),
                new Reading.HexagonBlock("38_-215", -34.9257, 138.5832, 20, 29.3, "station", 1.4, "Australia/Adelaide", "ADELAIDE METROPOLITAN", "SA_PW001",
                        new Reading.LandUseBlock("built_up", Map.of("built_up", 70, "grassland", 22, "water", 8), "grass", 22), "023000", "both",
                        Instant.parse("2026-09-18T12:00:00Z"), Instant.parse("2026-09-18T13:45:00Z"), Instant.parse("2026-09-18T14:45:00Z")),
                new Reading.Source("open-meteo", "best_match", "Weather data by Open-Meteo.com, CC BY 4.0", Instant.parse("2026-09-18T13:45:00Z"),
                        Instant.parse("2026-09-18T16:45:00Z"), "PT3H", false),
                at, current, "station",
                new Reading.StationBlock("023000", "ADELAIDE (WEST TERRACE / NGAYIRDAPIRA)", -34.9257, 138.5832, 29.32, 0.0, true, at, 15.0, 11.4, 3.2, 45,
                        11.0, 42, "NE", 17.0, 1025.8, 0.0, 0.0, 23.4, 8.1, 71.0, "Clear"),
                fire, flood, drought,
                List.of(new Reading.WarningBlock("IDS21037", "Severe Weather Warning", "for DAMAGING WINDS", "Damaging winds continuing", "SWW", "STD",
                        Instant.parse("2026-09-18T12:18:31Z"), Instant.parse("2026-09-18T12:18:26Z"), Instant.parse("2026-09-18T21:00:00Z"),
                        "http://reg.bom.gov.au/products/IDS21037.shtml")),
                new Reading.ForecastBlock(List.of(day), List.of(hour), change),
                new Reading.DriftBlock(at, "023000", "open-meteo", 1.2, -7, -2.5, null, 0.4, "temperature", false), null, Reading.DISCLAIMER);
    }

    static Reading empty() {
        return new Reading(Reading.SCHEMA, false, "no upstream answered and the hexagon holds no reading", new Reading.Point(-34.93, 138.6),
                null, null, null, null, null, null, null, null, null, List.of(), null, null, null, Reading.DISCLAIMER);
    }

    @Test
    void aFullReadingMatchesTheSchema() throws IOException {
        assertMatches(full());
    }

    @Test
    void anUnavailableReadingMatchesTheSchema() throws IOException {
        assertMatches(empty());
    }

    @Test
    void aReadingFromHistoryMatchesTheSchema() throws IOException {
        Reading f = full();
        Reading past = new Reading(f.schema(), true, null, f.point(), f.hexagon(), null, f.at(), f.current(), f.currentFrom(), f.station(),
                f.fire(), null, f.drought(), f.warnings(), null, null,
                new Reading.HistoryBlock(f.at(), Instant.parse("2026-09-18T13:52:00Z"), "INC0103", Instant.parse("2026-09-18T12:00:00Z")),
                f.disclaimer());
        assertMatches(past);
    }

    @Test
    void anUnknownFieldIsRejectedSoDriftIsCaught() throws IOException {
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(full()));
        ((tools.jackson.databind.node.ObjectNode) node).put("generatedAt", "2026-09-18T14:00:00Z");
        assertThat(schema().validate(node)).isNotEmpty();
    }

    @Test
    void theSchemaHasNoGeneratedAtSoAFingerprintCanWork() throws IOException {
        String json = MAPPER.writeValueAsString(full());
        assertThat(json).doesNotContain("generatedAt");
        assertThat(MAPPER.writeValueAsString(full())).isEqualTo(json);
    }

    /**
     * The Hub keeps a copy; when the two repositories sit side by side, as they do on the development
     * machine, the copies must be identical. Skipped on a machine that has only this repository.
     */
    @Test
    void theHubsCopyOfTheSchemaIsThisOne() throws IOException {
        Path hub = Path.of("..", "The-Hub-Database", "hub-services", "src", "main", "resources", "contract", "reading.schema.json");
        Assumptions.assumeTrue(Files.isRegularFile(hub), "The-Hub-Database is not beside this repository");
        Path ours = Path.of("src", "main", "resources", "contract", "reading.schema.json");
        // Line endings are the checkout's (the Hub checks out CRLF on Windows), not the contract's.
        assertThat(Files.readString(hub).replace("\r\n", "\n")).isEqualTo(Files.readString(ours).replace("\r\n", "\n"));
    }
}
