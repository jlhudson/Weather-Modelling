package au.gully;

import au.gully.bureau.StationFile;
import au.gully.bureau.StationRegistry;
import au.gully.platform.Hashing;
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
                assertThat(r.getBody()).contains("/css/map.css").contains("id=\"side\"").contains("id=\"legend\"").contains("id=\"detail\"").contains("/js/map.js");
            }
        }
        for (String feed : new String[]{"/console/map/stations.geojson", "/console/map/station/023000", "/console/map/status.json",
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
