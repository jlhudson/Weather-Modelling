package au.gully;

import au.gully.bureau.StationFile;
import au.gully.bureau.StationRegistry;
import au.gully.platform.Hashing;
import au.gully.reach.ReachRule;
import au.gully.reach.Terrain;
import au.gully.reach.TerrainStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one real end-to-end test: the service boots against a throwaway Postgres, runs its migration,
 * takes a station file into the register, and answers the API and the console. Nothing upstream is
 * called: {@code gully.enabled} is off.
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
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient db;

    @Autowired
    StationRegistry stations;

    @Autowired
    TerrainStore terrain;

    @Autowired
    ReachRule reachRule;

    @BeforeAll
    static void dockerOrSkip() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { /* read the status ourselves */ }).build();
    }

    /**
     * A key issued as the console would issue it: the hash in the table, the plaintext with the caller.
     */
    private void issueHubKey() {
        if (db.sql("select count(*) from api_key where consumer = 'hub'").query(Long.class).single() == 0) {
            db.sql("insert into api_key (consumer, created_at, created_by, key_hash, key_prefix, scope) values ('hub', now(), 'test', :hash, 'weather_end', 'ALL')")
                    .param("hash", Hashing.sha256Hex(HUB_KEY)).update();
        }
    }

    private void takeInTheFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/IDS60920-three-stations.xml")) {
            assertThat(in).isNotNull();
            List<StationFile.StationReading> readings = StationFile.parse(in.readAllBytes(), "sa");
            stations.accept(readings, Instant.now());
        }
    }

    @Test
    void theMigrationBuiltTheTablesAndTheStationsAreKept() throws Exception {
        for (String table : new String[]{"api_key", "console_user", "api_access_log", "log_event", "setting", "upstream_call", "station"}) {
            assertThat(db.sql("select to_regclass('public." + table + "')").query(String.class).single()).as(table).isEqualTo(table);
        }
        takeInTheFixture();
        assertThat(db.sql("select count(*) from station where state = 'sa'").query(Long.class).single()).isEqualTo(3L);
        assertThat(db.sql("select name from station where id = '023000'").query(String.class).single()).isEqualTo("ADELAIDE (WEST TERRACE / NGAYIRDAPIRA)");
        // A restart reads them back.
        stations.rehydrate();
        assertThat(stations.size()).isEqualTo(3);
    }

    @Test
    void theStationsAnswerWithAKeyAndRefuseWithout() throws Exception {
        issueHubKey();
        takeInTheFixture();
        ResponseEntity<Map> r = client().get().uri("/api/v1/stations.geojson").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("type", "FeatureCollection").containsEntry("stations", 3);
        assertThat(r.getHeaders().getFirst(HttpHeaders.ETAG)).as("fingerprinted").isNotNull();

        ResponseEntity<Map> one = client().get().uri("/api/v1/stations/023000").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody()).containsEntry("name", "ADELAIDE (WEST TERRACE / NGAYIRDAPIRA)").containsEntry("temperatureC", 15.0);
        assertThat(one.getBody().get("recent")).isInstanceOf(List.class);

        ResponseEntity<Map> missing = client().get().uri("/api/v1/stations/999999").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getHeaders().getContentType().toString()).contains("problem+json");

        ResponseEntity<Map> anonymous = client().get().uri("/api/v1/stations.geojson").retrieve().toEntity(Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).containsEntry("status", 401);
    }

    /**
     * A station's terrain planted as the sampler would keep it: flat at the station's height, so the
     * reach is a disc at the rule.
     */
    private void plantFlatTerrain(String id, double lat, double lon, double heightM) {
        double[] e = new double[Terrain.BEARINGS * Terrain.STEPS];
        java.util.Arrays.fill(e, heightM);
        terrain.put(new Terrain(id, lat, lon, heightM, e, Instant.now(), 25));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReachIsDrawnFromTheTerrainUnderTheRuleAndTheRuleIsKept() throws Exception {
        issueHubKey();
        takeInTheFixture();
        // No terrain yet: the collection is empty, and the station's drawer says so.
        ResponseEntity<Map> none = client().get().uri("/api/v1/reach.geojson").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(none.getBody()).containsEntry("sampled", 0).containsEntry("inForce", true);
        Map<String, Object> before = client().get().uri("/api/v1/stations/023000").header("X-Api-Key", HUB_KEY).retrieve().body(Map.class);
        assertThat((Map<String, Object>) before.get("terrain")).containsEntry("sampled", false).containsEntry("points", 2401);
        assertThat(before.get("reach")).isNull();

        plantFlatTerrain("023000", -34.9257, 138.5832, 29);
        ResponseEntity<Map> one = client().get().uri("/api/v1/reach.geojson").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(one.getBody()).containsEntry("sampled", 1);
        List<Map<String, Object>> features = (List<Map<String, Object>>) one.getBody().get("features");
        Map<String, Object> props = (Map<String, Object>) features.getFirst().get("properties");
        assertThat(props).containsEntry("id", "023000").containsEntry("minKm", ReachRule.DEFAULT_KM).containsEntry("maxKm", ReachRule.DEFAULT_KM);
        Map<String, Object> geometry = (Map<String, Object>) features.getFirst().get("geometry");
        assertThat(geometry).containsEntry("type", "Polygon");
        assertThat(((List<List<List<Double>>>) geometry.get("coordinates")).getFirst()).hasSize(Terrain.BEARINGS + 1);
        Map<String, Object> after = client().get().uri("/api/v1/stations/023000").header("X-Api-Key", HUB_KEY).retrieve().body(Map.class);
        assertThat((Map<String, Object>) after.get("terrain")).containsEntry("sampled", true).containsEntry("elevationM", 29.0);
        assertThat((Map<String, Object>) after.get("reach")).containsEntry("meanKm", ReachRule.DEFAULT_KM);

        // The probe (W-5): a point 10 km north of Adelaide is inside its reach; the other two stations are outside, with why.
        double[] north = au.gully.reach.Geo.destination(-34.9257, 138.5832, 0, 10);
        Map<String, Object> at = client().get().uri("/api/v1/stations/at?lat=" + north[0] + "&lon=" + north[1]).header("X-Api-Key", HUB_KEY).retrieve().body(Map.class);
        List<Map<String, Object>> inReach = (List<Map<String, Object>>) at.get("inReach");
        assertThat(inReach).hasSize(1);
        assertThat(inReach.getFirst()).containsEntry("id", "023000").containsEntry("km", 10.0).containsEntry("rayKm", 40.0).containsEntry("margin", 30.0);
        assertThat((Integer) inReach.getFirst().get("bearingDeg")).isEqualTo(180);
        List<Map<String, Object>> outside = (List<Map<String, Object>>) at.get("outside");
        assertThat(outside).allSatisfy(s -> assertThat((String) s.get("why")).contains("not sampled"));
        assertThat(at).containsEntry("water", false);
        double[] farOut = au.gully.reach.Geo.destination(-34.9257, 138.5832, 90, 45);
        Map<String, Object> beyond = client().get().uri("/api/v1/stations/at?lat=" + farOut[0] + "&lon=" + farOut[1]).header("X-Api-Key", HUB_KEY).retrieve().body(Map.class);
        assertThat((List<?>) beyond.get("inReach")).isEmpty();
        assertThat((String) ((List<Map<String, Object>>) beyond.get("outside")).getFirst().get("why")).contains("stops at 40.0 km");
        ResponseEntity<Map> bad = client().get().uri("/api/v1/stations/at?lat=95&lon=0").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The rule set from the console is what the API draws by, and a restart reads it back.
        reachRule.set(20, 5, 15, "test", Instant.now());
        ResponseEntity<Map> narrower = client().get().uri("/api/v1/reach.geojson").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat((Map<String, Object>) narrower.getBody().get("rule")).containsEntry("reachKm", 20.0).containsEntry("kmPer100m", 5.0);
        reachRule.rehydrate();
        assertThat(reachRule.current()).isEqualTo(new ReachRule.Rule(20, 5, 15));
        assertThat(reachRule.by()).isEqualTo("test");
        // The terrain too.
        terrain.rehydrate();
        assertThat(terrain.get("023000")).isPresent();
        assertThat(terrain.get("023000").get().at(3, 3)).isEqualTo(29);
        reachRule.set(ReachRule.DEFAULT_KM, ReachRule.DEFAULT_KM_PER_100M, ReachRule.DEFAULT_COASTAL_KM, "test", Instant.now());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDiagnosticsAnswerTheMorningAgent() {
        issueHubKey();
        ResponseEntity<Map> r = client().get().uri("/api/diagnostics?window=PT6H").header("X-Api-Key", HUB_KEY).retrieve().toEntity(Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = r.getBody();
        assertThat(body).containsKeys("app", "startup", "logs", "gully");
        Map<String, Object> gully = (Map<String, Object>) body.get("gully");
        assertThat(gully).containsKeys("upstreams", "bureau", "held");
        assertThat((Map<String, Object>) gully.get("bureau")).containsEntry("state", "sa");
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
    void theConsoleLogsInAndEveryPageRenders() throws Exception {
        takeInTheFixture();
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

        for (String page : new String[]{"/console/map", "/console/upstreams", "/console/upstreams?calls=all", "/console/diagnostics", "/console/api-keys"}) {
            ResponseEntity<String> r = client().get().uri(page).header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class);
            assertThat(r.getStatusCode()).as(page).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).as(page).contains("bootstrap.min.css").contains("console.js").contains("/logout");
            if (page.equals("/console/map")) {
                assertThat(r.getBody()).contains("/css/map.css").contains("id=\"side\"").contains("id=\"legend\"").contains("id=\"detail\"").contains("/js/map.js").contains("id=\"reachKm\"").contains("id=\"kmPer100m\"");
            }
        }
        for (String feed : new String[]{"/console/map/stations.geojson", "/console/map/station/023000", "/console/map/status.json", "/console/map/reach.geojson", "/console/map/reach.geojson?km=20&kmPer100m=5&coastalKm=15", "/console/map/probe?lat=-34.9&lon=138.6",
                "/console/upstreams/spend.json", "/console/diagnostics/summary.json", "/actuator/prometheus"}) {
            ResponseEntity<String> r = client().get().uri(feed).header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class);
            assertThat(r.getStatusCode()).as(feed).isEqualTo(HttpStatus.OK);
        }
        String points = client().get().uri("/console/map/stations.geojson").header(HttpHeaders.COOKIE, session).retrieve().toEntity(String.class).getBody();
        assertThat(points).contains("\"stations\":3").contains("\"fresh\":");
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
