package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bureau's files as they were read live on 18 September 2026: three stations cut from the
 * South Australian observations product, the Tasmanian warnings listing, and the severe weather
 * warning product it pointed at (its image removed).
 */
class BureauFilesTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = BureauFilesTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void theStationFileGivesEveryStationItsDetailsAndItsLatestValues() throws Exception {
        List<StationFile.StationReading> readings = StationFile.parse(fixture("IDS60920-three-stations.xml"), "sa");
        assertThat(readings).hasSize(3);
        StationFile.StationReading adelaide = readings.getFirst();
        Station s = adelaide.station();
        assertThat(s.id()).isEqualTo("023000");
        assertThat(s.wmoId()).isEqualTo("94648");
        assertThat(s.name()).isEqualTo("ADELAIDE (WEST TERRACE / NGAYIRDAPIRA)");
        assertThat(s.lat()).isEqualTo(-34.9257);
        assertThat(s.lon()).isEqualTo(138.5832);
        assertThat(s.heightM()).isEqualTo(29.32);
        assertThat(s.zone()).isEqualTo("Australia/Adelaide");
        assertThat(s.district()).isEqualTo("SA_PW001");
        assertThat(s.state()).isEqualTo("sa");

        Observation o = adelaide.observation();
        assertThat(o.at()).isEqualTo(Instant.parse("2026-09-18T13:50:00Z"));
        assertThat(o.temperatureC()).isEqualTo(15.0);
        assertThat(o.apparentTemperatureC()).isEqualTo(11.4);
        assertThat(o.dewPointC()).isEqualTo(3.2);
        assertThat(o.humidityPct()).isEqualTo(45);
        assertThat(o.windSpeedKmh()).isEqualTo(11.0);
        assertThat(o.windDirectionDeg()).isEqualTo(42);
        assertThat(o.windDirection()).isEqualTo("NE");
        assertThat(o.windGustKmh()).isEqualTo(17.0);
        assertThat(o.pressureMslHpa()).isEqualTo(1025.8);
        assertThat(o.rainSince9amMm()).isEqualTo(0.0);
        assertThat(o.rain24hMm()).isEqualTo(0.0);
        assertThat(o.maxTemperatureC()).isEqualTo(23.4);
        assertThat(o.visibilityKm()).isEqualTo(71.0);
        assertThat(o.cloud()).isEqualTo("Clear");
        assertThat(o.cloudOktas()).isEqualTo(0);
        assertThat(o.fireInputsPresent()).isTrue();
    }

    @Test
    void theStationUrlsAreTheSevenStateProducts() {
        assertThat(StationFile.url("sa")).isEqualTo("https://reg.bom.gov.au/fwo/IDS60920.xml");
        assertThat(StationFile.url("nsw")).isEqualTo("https://reg.bom.gov.au/fwo/IDN60920.xml");
        assertThat(StationFile.PRODUCTS).hasSize(7);
    }

    @Test
    void theWarningsListingNamesTheProductsAndSkipsTheMarineSummaryPage() throws Exception {
        List<WarningFiles.Item> items = WarningFiles.parseFeed(fixture("IDZ00058.warnings_tas.xml"));
        // The listing holds two items: a marine wind summary (a page, no product) and the severe weather warning.
        assertThat(items).extracting(WarningFiles.Item::productId).containsExactly("IDT21037");
        assertThat(items.getFirst().title()).contains("Severe Weather Warning");
        assertThat(items.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-09-18T12:18:39Z"));
    }

    @Test
    void theWarningProductCarriesItsDistrictsAndItsTimes() throws Exception {
        Warning w = WarningFiles.parseProduct(fixture("IDT21037.xml"), "tas", "http://reg.bom.gov.au/products/IDT21037.shtml");
        assertThat(w).isNotNull();
        assertThat(w.id()).isEqualTo("IDT21037");
        assertThat(w.title()).isEqualTo("Severe Weather Warning");
        assertThat(w.phenomena()).isEqualTo("for DAMAGING WINDS");
        assertThat(w.headline()).isEqualTo("Damaging winds continuing, easing during Saturday morning.");
        assertThat(w.hazard()).isEqualTo("SWW");
        assertThat(w.districts()).containsExactly("TAS_PW010", "TAS_PW004", "TAS_PW002", "TAS_PW003", "TAS_PW005", "TAS_PW007", "TAS_PW006", "TAS_PW008", "TAS_PW009");
        assertThat(w.issuedAt()).isEqualTo(Instant.parse("2026-09-18T12:18:31Z"));
        assertThat(w.from()).isEqualTo(Instant.parse("2026-09-18T12:18:26Z"));
        assertThat(w.until()).isEqualTo(Instant.parse("2026-09-18T21:00:00Z"));
        assertThat(w.covers("TAS_PW006")).isTrue();
        assertThat(w.covers("SA_PW001")).isFalse();
        assertThat(w.currentAt(Instant.parse("2026-09-18T15:00:00Z"))).isTrue();
        assertThat(w.currentAt(Instant.parse("2026-09-19T15:00:00Z"))).isFalse();
        assertThat(w.fireWeather()).isFalse();
    }
}
