package au.gully;

import au.gully.platform.Hashing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one real end-to-end test (docs/06 item 13): the service boots against a throwaway Postgres
 * that already holds the four tables the old service's Hibernate built — with a key issued to the
 * Hub in it — runs its migration, and answers the API. Nothing upstream is called:
 * {@code gully.enabled} is off, so the reading is honestly unavailable and says so in the contract's
 * shape.
 * <p>
 * Skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"gully.enabled=false", "gully.console.code=12345678", "spring.flyway.clean-disabled=true"})
class EndToEndTest {

    static final String HUB_KEY = "weather_end-to-end-test-key-for-the-hub";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("fixtures/before-gully.sql");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient db;

    @BeforeAll
    static void dockerOrSkip() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { /* read the status ourselves */ }).build();
    }

    @Test
    void theMigrationKeptTheOldKeyAndBuiltTheNewTables() {
        // The legacy row is still there, with the hash of the plaintext the init script planted.
        String hash = db.sql("select key_hash from api_key where consumer = 'hub'").query(String.class).single();
        assertThat(hash).isEqualTo(Hashing.sha256Hex(HUB_KEY));
        for (String table : new String[]{"hexagon", "drought_day", "archive_day", "model_now", "station", "station_sample", "upstream_call", "grass_curing", "river_discharge", "grid_spec", "forecast_drift", "station_recent", "setting"}) {
            Long n = db.sql("select count(*) from " + table).query(Long.class).single();
            assertThat(n).as(table).isNotNull();
        }
        Long userCount = db.sql("select count(*) from console_user where username = 'operator'").query(Long.class).single();
        assertThat(userCount).isEqualTo(1);
        // The grid the tables were written with was recorded on the first start.
        assertThat(db.sql("select spec from grid_spec where id = 1").query(String.class).single()).isEqualTo(store.grid().spec());
    }

    @Test
    void aReadingIsAnsweredInTheContractsShapeEvenWhenNothingCanBeFetched() {
        ResponseEntity<Map> r = client().get().uri("/api/v1/readings?lat=-34.93&lon=138.6&forecast=true")
                .header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(r.getHeaders().getETag()).isNotNull();
        assertThat(r.getHeaders().getCacheControl()).contains("max-age");
        assertThat(r.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("600");
        Map<?, ?> body = r.getBody();
        assertThat(body.get("schema")).isEqualTo("gully/reading/1");
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("unavailable")).asString().contains("no upstream");
        assertThat(body.get("hexagon")).as("the hexagon was created and is described").isNotNull();
        assertThat(((Map<?, ?>) body.get("hexagon")).get("id")).isNotNull();

        // The same again with the ETag is a 304.
        ResponseEntity<Void> again = client().get().uri("/api/v1/readings?lat=-34.93&lon=138.6&forecast=true")
                .header("X-Api-Key", HUB_KEY).header(HttpHeaders.IF_NONE_MATCH, r.getHeaders().getETag()).retrieve().toBodilessEntity();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void theHexagonLayerIsFingerprinted() {
        ResponseEntity<String> r = client().get().uri("/api/v1/hexagons.geojson").header("X-Api-Key", HUB_KEY).retrieve().toEntity(String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getETag()).isNotNull();
        assertThat(r.getBody()).contains("\"FeatureCollection\"");
        ResponseEntity<Void> again = client().get().uri("/api/v1/hexagons.geojson").header("X-Api-Key", HUB_KEY)
                .header(HttpHeaders.IF_NONE_MATCH, r.getHeaders().getETag()).retrieve().toBodilessEntity();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void errorsAreProblemDetails() {
        ResponseEntity<Map> noKey = client().get().uri("/api/v1/readings?lat=-34.93&lon=138.6").retrieve().toEntity(Map.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noKey.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(noKey.getBody()).containsEntry("status", 401);

        ResponseEntity<Map> bad = client().get().uri("/api/v1/readings?lat=95&lon=138.6").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(bad.getBody().get("detail")).asString().contains("lat");

        ResponseEntity<Map> missing = client().get().uri("/api/v1/readings?lat=-34.93").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }

    @Test
    void theContractAndTheOpenApiDocumentAreOpen() {
        ResponseEntity<String> schema = client().get().uri("/api/v1/contract/reading.schema.json").retrieve().toEntity(String.class);
        assertThat(schema.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(schema.getBody()).contains("gully/reading/1");
        ResponseEntity<String> openapi = client().get().uri("/api/v1/openapi.json").retrieve().toEntity(String.class);
        assertThat(openapi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openapi.getBody()).contains("/api/v1/readings").contains("/api/v1/fire-indices");
    }

    @Test
    void theFireIndicesCalculatorAndStatusAnswer() {
        ResponseEntity<Map> r = client().get().uri("/api/v1/fire-indices?temperatureC=30&humidityPct=20&windKmh=30&droughtFactor=10&curingPct=100&condition=natural")
                .header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> grass = (Map<?, ?>) r.getBody().get("grass");
        assertThat(grass.get("fbi")).isEqualTo(47);
        assertThat(grass.get("afdrsRating")).isEqualTo("High");
        ResponseEntity<Map> status = client().get().uri("/api/v1/status").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).containsKeys("upstreams", "sources", "held");
    }

    /**
     * The three named routes (W-20): now is the reading cut to the ground's half, in the reading's
     * shape; forecast is the whole reading; drought is its own shape with the days behind it. All
     * behind the same key and scope, all honest when nothing can be fetched.
     */
    @Test
    void nowForecastAndDroughtAnswerByName() {
        ResponseEntity<Map> now = client().get().uri("/api/v1/now?lat=-34.93&lon=138.6").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(now.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(now.getBody().get("schema")).isEqualTo("gully/reading/1");
        assertThat(now.getBody()).containsKeys("current", "currentFrom", "station", "fire", "warnings").containsEntry("forecast", null)
                .containsEntry("drought", null).containsEntry("flood", null);
        ResponseEntity<Map> forecast = client().get().uri("/api/v1/forecast?lat=-34.93&lon=138.6").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(forecast.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forecast.getBody().get("schema")).isEqualTo("gully/reading/1");
        assertThat(forecast.getBody()).containsKeys("current", "forecast", "drought", "fire", "flood");
        ResponseEntity<Map> drought = client().get().uri("/api/v1/drought?lat=-34.93&lon=138.6&days=7").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(drought.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(drought.getBody().get("schema")).isEqualTo("gully/drought/1");
        assertThat(drought.getBody().get("available")).as("no upstream: the spin-up cannot be fed").isEqualTo(false);
        assertThat(drought.getBody()).containsKeys("hexagon", "stations", "days", "rain");
        assertThat(((Map<?, ?>) drought.getBody().get("rain")).get("last7DaysMm")).as("the record does not reach a week back").isNull();
        assertThat(client().get().uri("/api/v1/drought?lat=-34.93&lon=138.6").retrieve().toEntity(Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client().get().uri("/api/v1/now?lat=-34.93&lon=10").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theLegacyRouteStillAnswersInItsOldShape() {
        ResponseEntity<Map> r = client().get().uri("/api/weather?lat=-34.93&lon=138.6&forecast=true").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKeys("generatedAt", "query", "provenance", "current", "fire", "flood", "drought", "disclaimer");
    }

    @Autowired
    au.gully.bureau.StationRegistry stations;
    @Autowired
    au.gully.cfs.Curing curing;
    @Autowired
    au.gully.upstreams.Ledger ledger;
    @Autowired
    au.gully.hexagons.HexagonRepository hexagons;
    @Autowired
    au.gully.hexagons.History history;
    @Autowired
    au.gully.hexagons.HexagonStore store;
    @Autowired
    au.gully.hexagons.Drifts drifts;
    @Autowired
    au.gully.hexagons.Reach reach;
    @Autowired
    au.gully.drought.DroughtDays droughtDays;
    @Autowired
    au.gully.drought.ArchiveDays archiveDays;

    /**
     * Every piece of SQL, once, against the real database: the station register and its ledger, the
     * curing register, the upstream ledger and its windows, the hexagon row and its reload, a snapshot.
     */
    @Test
    void everyTableIsWrittenAndReadBack() throws Exception {
        byte[] xml;
        try (java.io.InputStream in = getClass().getResourceAsStream("/fixtures/IDS60920-three-stations.xml")) {
            xml = in.readAllBytes();
        }
        java.time.Instant now = java.time.Instant.now();
        // The fixture was read on 18 September 2026; a station is "now" only for an hour, so re-time it to now.
        // The third station reports no temperature at all, as a rain-only station does: the ledger must take it.
        java.util.List<au.gully.bureau.StationFile.StationReading> fresh = new java.util.ArrayList<>();
        for (au.gully.bureau.StationFile.StationReading r : au.gully.bureau.StationFile.parse(xml, "sa")) {
            au.gully.bureau.Observation o = r.observation();
            boolean rainOnly = fresh.size() == 2;
            fresh.add(new au.gully.bureau.StationFile.StationReading(r.station(), new au.gully.bureau.Observation(o.stationId(), now,
                    rainOnly ? null : o.temperatureC(), o.apparentTemperatureC(), o.dewPointC(), o.humidityPct(), o.windSpeedKmh(), o.windDirectionDeg(),
                    o.windDirection(), o.windGustKmh(), o.pressureMslHpa(), o.rainSince9amMm(), o.rain24hMm(), rainOnly ? null : o.maxTemperatureC(),
                    o.minTemperatureC(), o.visibilityKm(), o.cloud(), o.cloudOktas(), o.deltaTC())));
        }
        int added = stations.accept(fresh, now);
        assertThat(added).isEqualTo(3);
        assertThat(stations.size()).isEqualTo(3);
        // What the reader does after a file: every hexagon re-finds its station, every station gets one.
        store.stationsChanged();
        assertThat(store.size()).isGreaterThanOrEqualTo(3);
        // The reach (W-18): the default until set; set, it is written, the stations count for more hexagons
        // and every station gets them; reloaded with the registers, it is still the value set. Put back after.
        assertThat(reach.isDefault()).isTrue();
        int held = store.size(), reached = stations.hexagonsOf(store.grid(), "023000").size();
        assertThat(reach.set(12.3, "test")).as("held to the slider's quarter-kilometre step").isEqualTo(12.25);
        assertThat(stations.hexagonsOf(store.grid(), "023000").size()).isGreaterThan(reached);
        assertThat(store.stationsChanged()).isGreaterThan(0);
        assertThat(store.size()).isGreaterThan(held);
        reach.rehydrate();
        assertThat(reach.km()).isEqualTo(12.25);
        assertThat(reach.by()).isEqualTo("test");
        assertThat(reach.since()).isNotNull();
        reach.set(au.gully.hexagons.Grid.DEFAULT_STATION_REACH_KM, "test");
        store.stationsChanged();
        assertThat(stations.hexagonsOf(store.grid(), "023000").size()).isEqualTo(reached);
        assertThat(stations.ledgerRows()).isEqualTo(3);
        assertThat(stations.nearest(-34.93, 138.6)).isPresent();
        assertThat(stations.nearest(-34.93, 138.6).get().station().id()).isEqualTo("023000");
        java.util.List<au.gully.bureau.Station> all = new java.util.ArrayList<>(stations.all());
        java.time.LocalDate day = java.time.LocalDate.of(2026, 9, 18);
        assertThat(stations.daily(all, day.minusDays(2), day.plusDays(1))).isNotNull();
        assertThat(stations.recentSamples("023000", 5)).hasSize(1);
        // The last readings (W-16): a second file a moment on with the wind swung round is kept, survives
        // a reload of the register, and reads as a wind change.
        java.util.List<au.gully.bureau.StationFile.StationReading> later = new java.util.ArrayList<>();
        for (au.gully.bureau.StationFile.StationReading r : fresh) {
            au.gully.bureau.Observation o = r.observation();
            later.add(new au.gully.bureau.StationFile.StationReading(r.station(), new au.gully.bureau.Observation(o.stationId(), now.plusSeconds(1),
                    o.temperatureC(), o.apparentTemperatureC(), o.dewPointC(), o.humidityPct(), 30.0, 225, "SW", 40.0, o.pressureMslHpa(),
                    o.rainSince9amMm(), o.rain24hMm(), o.maxTemperatureC(), o.minTemperatureC(), o.visibilityKm(), o.cloud(), o.cloudOktas(), o.deltaTC())));
        }
        stations.accept(later, now.plusSeconds(1));
        assertThat(stations.recent("023000")).hasSize(2);
        assertThat(stations.recent("023000").getFirst().windDirectionDeg()).isEqualTo(225);
        stations.rehydrate();
        assertThat(stations.recent("023000")).hasSize(2);
        assertThat(stations.recent("023000").getFirst().windSpeedKmh()).isEqualTo(30.0);
        au.gully.bureau.Observation first = fresh.getFirst().observation();
        if (first.windSpeedKmh() != null && first.windSpeedKmh() >= au.gully.bureau.WindShift.CALM_KMH && first.windDirectionDeg() != null
                && au.gully.bureau.WindShift.angle(first.windDirectionDeg(), 225) >= au.gully.bureau.WindShift.SWING_SLIGHT_DEG) {
            assertThat(stations.windShift("023000")).isPresent();
        }

        au.gully.cfs.Curing.Entry e = curing.save("Mount Lofty Ranges", 80, day, "CFS map", "test");
        assertThat(e.district()).isEqualTo("MOUNT LOFTY RANGES");
        assertThat(curing.forDistrict("mount lofty ranges")).isPresent();
        curing.rehydrate();
        assertThat(curing.forDistrict("MOUNT LOFTY RANGES").get().percent()).isEqualTo(80);

        ledger.record("open-meteo", 5.0, true, java.time.Duration.ofMillis(120), "forecast 1_2");
        ledger.record("open-meteo", 5.0, false, java.time.Duration.ofMillis(9), "forecast 1_2: unreachable");
        assertThat(ledger.spent("open-meteo", java.time.Duration.ofHours(1))).isEqualTo(10.0);
        assertThat(ledger.daily("open-meteo", java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1), java.time.LocalDate.now(java.time.ZoneOffset.UTC))).hasSize(2);
        assertThat(ledger.hourly("open-meteo", now.minus(java.time.Duration.ofHours(2)))).isNotEmpty();
        assertThat(ledger.recent(10)).hasSize(2);

        au.gully.hexagons.Hexagon h = store.ask(-34.93, 138.6, false, "INC0001");
        assertThat(h.active()).isTrue();
        assertThat(h.stationId()).as("Adelaide West Terrace is inside this hexagon").isEqualTo("023000");
        assertThat(hexagons.loadAll(store.grid())).extracting(au.gully.hexagons.Hexagon::id).contains(h.id());
        assertThat(hexagons.loadAll(store.grid()).stream().filter(x -> x.id().equals(h.id())).findFirst().get().stationId()).isEqualTo("023000");
        // History is the ground's (W-19): the ask wrote nothing, and what was "now" at that moment is the
        // station's ledger row - the first file's reading, consolidated - found within three hours of it,
        // for the reading and for the map's timeline alike.
        assertThat(history.then(h, now)).isPresent();
        assertThat(history.then(h, now).get().from()).isEqualTo("station");
        assertThat(history.then(h, now).get().stationId()).isEqualTo("023000");
        assertThat(history.then(h, now).get().conditions().temperatureC())
                .isEqualTo(fresh.stream().filter(x -> x.station().id().equals("023000")).findFirst().get().observation().temperatureC());
        assertThat(history.allAt(store.all(), now.plusSeconds(60))).containsKey(h.id());
        assertThat(history.then(h, now.plus(Duration.ofHours(4)))).as("nothing stands beyond three hours").isEmpty();
        assertThat(history.of(h, 5)).hasSize(1);
        assertThat(history.modelRows()).as("the model never stood in: no upstream is enabled").isZero();
        // The consolidation: the second file's reading went into the window after the row was written, so the
        // row counts the one reading it was made from.
        assertThat(stations.recentSamples("023000", 1).getFirst().get("readings")).isEqualTo(1);
        // The drought's days are the hexagon's record: nothing could be spun up with no upstream, so none yet.
        assertThat(history.droughtDays()).isZero();
        assertThat(droughtDays.save(h.id(), java.util.List.of(new au.gully.drought.DroughtDays.Day(day, 2.5, 24.0, "archive")), now)).isEqualTo(1);
        assertThat(droughtDays.of(h.id(), day, day).get(day).source()).isEqualTo("archive");
        assertThat(droughtDays.save(h.id(), java.util.List.of(new au.gully.drought.DroughtDays.Day(day, 9.9, 30.0, "stations")), now)).as("a day held is left as it was").isZero();
        assertThat(droughtDays.recent(h.id(), 10)).hasSize(1);
        // The archive as fetched (W-21): kept whole per point, the archive's day replacing a recent-days row, never the reverse;
        // and a re-spin from the record alone, with no upstream, finds a year it cannot supply and leaves the state as it was.
        java.util.List<au.gully.upstreams.OpenMeteo.DailyRow> fetched = java.util.List.of(new au.gully.upstreams.OpenMeteo.DailyRow(day, 1.0, 20.0), new au.gully.upstreams.OpenMeteo.DailyRow(day.plusDays(1), 0.0, 22.0));
        assertThat(archiveDays.save(h.cell().lat(), h.cell().lon(), fetched, "recent", now)).isEqualTo(2);
        assertThat(archiveDays.save(h.cell().lat(), h.cell().lon(), java.util.List.of(new au.gully.upstreams.OpenMeteo.DailyRow(day, 3.0, 21.0)), "archive", now)).as("the archive replaces a recent row").isEqualTo(1);
        assertThat(archiveDays.save(h.cell().lat(), h.cell().lon(), java.util.List.of(new au.gully.upstreams.OpenMeteo.DailyRow(day, 9.0, 30.0)), "recent", now)).as("a recent row never replaces the archive's").isZero();
        assertThat(archiveDays.of(h.cell().lat(), h.cell().lon(), day, day.plusDays(1))).hasSize(2);
        assertThat(archiveDays.of(h.cell().lat(), h.cell().lon(), day, day).get(day).rainMm()).isEqualTo(3.0);
        assertThat(archiveDays.count()).isEqualTo(2);
        assertThat(store.respinDroughts()).as("no hexagon holds a drought to remake").isZero();
        assertThat(history.prune(now)).as("nothing is five years old").isZero();

        // The drift ledger takes a blend as its judge: three stations joined is twenty characters, which the
        // first shape of the table refused, and with it the reading (V8).
        db.sql("""
                insert into forecast_drift (hexagon_id, at, station_id, upstream, temperature_c, humidity_pct, wind_kmh, rain_mm, score, worst, drifted, created_at)
                values (:h, :at, :s, :u, 1.5, -4, 2.0, null, 0.5, 'temperature', false, :at)""")
                .param("h", h.id()).param("at", au.gully.storage.Db.ts(now)).param("s", "023000+023034+023090").param("u", "open-meteo").update();
        assertThat(drifts.recent(h.id(), 5)).extracting(au.gully.hexagons.Drift::stationId).contains("023000+023034+023090");
    }

    @Test
    void healthIsPublicAndReadyIncludesTheDatabase() {
        ResponseEntity<Map> r = client().get().uri("/actuator/health/readiness").retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "UP");
    }

    /**
     * The console: the login with the code, then every page renders behind it with Bootstrap and the
     * console script on the page, and the map feeds answer. The browser is the operator's; this is
     * the same walk with a cookie jar.
     */
    @Test
    void theConsoleLogsInAndEveryPageRenders() {
        ResponseEntity<String> loginPage = client().get().uri("/login").retrieve().toEntity(String.class);
        assertThat(loginPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginPage.getBody()).contains("name=\"code\"").contains("bootstrap.min.css");
        String cookie = firstCookie(loginPage.getHeaders());
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"|value=\"([^\"]+)\"[^>]*name=\"_csrf\"").matcher(loginPage.getBody());
        assertThat(m.find()).as("the login form carries a CSRF token").isTrue();
        String csrf = m.group(1) != null ? m.group(1) : m.group(2);

        ResponseEntity<Void> wrong = client().post().uri("/login").header(HttpHeaders.COOKIE, cookie)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=operator&code=00000000&_csrf=" + csrf).retrieve().toEntity(Void.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(wrong.getHeaders().getLocation().toString()).endsWith("/login?error");

        ResponseEntity<Void> login = client().post().uri("/login").header(HttpHeaders.COOKIE, cookie)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=operator&code=12345678&_csrf=" + csrf).retrieve().toEntity(Void.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(login.getHeaders().getLocation().toString()).endsWith("/console/map");
        String session = login.getHeaders().containsHeader(HttpHeaders.SET_COOKIE) ? firstCookie(login.getHeaders()) : cookie;

        for (String page : new String[]{"/console/map", "/console/hexagons", "/console/upstreams", "/console/curing",
                "/console/diagnostics", "/console/api-keys"}) {
            ResponseEntity<String> r = client().get().uri(page).header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class);
            assertThat(r.getStatusCode()).as(page).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).as(page).contains("bootstrap.min.css").contains("console.js").contains("/logout");
            if (page.equals("/console/map")) {
                // The map page: its own sheet in the head, the rail, the figures, the legend and the timeline.
                assertThat(r.getBody()).contains("/css/map.css").contains("id=\"rail\"").contains("id=\"timeline\"").contains("id=\"legend\"").contains("id=\"stats\"");
            }
        }
        for (String feed : new String[]{"/console/map/layer.geojson", "/console/map/grid.geojson?south=-35.2&west=138.3&north=-34.7&east=138.9",
                "/console/map/stations.geojson", "/console/map/coverage.geojson?reachKm=10", "/console/map/sources.json", "/console/diagnostics/summary.json", "/actuator/prometheus"}) {
            ResponseEntity<String> r = client().get().uri(feed).header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class);
            assertThat(r.getStatusCode()).as(feed).isEqualTo(HttpStatus.OK);
        }
        // The timeline: behind now the layer is the history, ahead of now the forecasts, and the layer says which.
        String ahead = client().get().uri("/console/map/layer.geojson?at=" + Instant.now().plus(Duration.ofHours(6)))
                .header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class).getBody();
        assertThat(ahead).contains("\"mode\":\"ahead\"").contains("\"aheadHours\":72");
        String behind = client().get().uri("/console/map/layer.geojson?at=" + Instant.now().minus(Duration.ofHours(6)))
                .header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class).getBody();
        assertThat(behind).contains("\"mode\":\"history\"");
        // The stations as points say whether each is fresh.
        String points = client().get().uri("/console/map/stations.geojson").header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class).getBody();
        assertThat(points).contains("\"meta\":").contains("\"fresh\":");
        // Without the cookie, the console is the login page.
        ResponseEntity<Void> anonymous = client().get().uri("/console/map").retrieve().toEntity(Void.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(anonymous.getHeaders().getLocation().toString()).endsWith("/login");
    }

    private static String firstCookie(HttpHeaders headers) {
        java.util.List<String> set = headers.get(HttpHeaders.SET_COOKIE);
        assertThat(set).as("a session cookie").isNotEmpty();
        return set.getFirst().split(";", 2)[0];
    }
}
