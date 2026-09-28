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
 * warning product it pointed at (its image removed); and three stations cut from the Tasmanian
 * observations product as read on 29 September 2026 - Hobart, and two of the ten in Antarctica.
 */
class BureauFilesTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = BureauFilesTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void aStationTaggedUtcKeepsTheStateClockAndOnlySeaLevelPressureIsPressure() throws Exception {
        StationFile.StationReading thevenard = StationFile.parse(fixture("IDS60920-three-stations.xml"), "sa").stream()
                .filter(r -> r.station().id().equals("018207")).findFirst().orElseThrow();
        // The file says tz="UTC": its day would turn at 6:30 pm. It is in South Australia.
        assertThat(thevenard.station().zone()).isEqualTo("Australia/Adelaide");
        // It gives station-level pressure and no msl_pres: not blended with sea-level pressures.
        assertThat(thevenard.observation().pressureMslHpa()).isNull();
    }

    @Test
    void theTasmanianFileKeepsHobartsClockAndLeavesAntarcticaOut() throws Exception {
        byte[] xml = fixture("IDT60920-three-stations.xml");
        // Hobart, Casey (tagged Australia/Casey) and McMurdo (tagged UTC) are in the file; only Hobart is Tasmania's ground.
        List<StationFile.StationReading> readings = StationFile.parse(xml, "tas");
        assertThat(readings).extracting(r -> r.station().name()).containsExactly("HOBART (ELLERSLIE ROAD)");
        Station hobart = readings.getFirst().station();
        assertThat(hobart.zone()).isEqualTo("Australia/Hobart");
        assertThat(hobart.state()).isEqualTo("tas");
        assertThat(hobart.district()).isEqualTo("TAS_PW006");
        assertThat(readings.getFirst().observation().temperatureC()).isEqualTo(7.6);
        // A Tasmanian station tagged UTC, as Thevenard is in South Australia's file, keeps Hobart's clock, not Adelaide's.
        byte[] utc = new String(xml, java.nio.charset.StandardCharsets.UTF_8)
                .replace("tz=\"Australia/Hobart\" stn-name=\"HOBART", "tz=\"UTC\" stn-name=\"HOBART").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(StationFile.parse(utc, "tas").getFirst().station().zone()).isEqualTo("Australia/Hobart");
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
    }

    @Test
    void theStationFilesAreSouthAustraliasAndTasmanias() {
        assertThat(StationReader.STATES.stream().map(StationFile::url)).containsExactly(
                "https://reg.bom.gov.au/fwo/IDS60920.xml", "https://reg.bom.gov.au/fwo/IDT60920.xml");
        assertThat(StationFile.PRODUCTS).hasSize(7);
        assertThat(StationFile.ZONES).containsOnlyKeys(StationFile.PRODUCTS.keySet());
    }
}
