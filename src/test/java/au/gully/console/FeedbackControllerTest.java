package au.gully.console;

import au.gully.feedback.FeedbackThrottle;
import au.gully.feedback.HubFeedback;
import au.gully.feedback.StubHub;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feedback page's two answers - JSON to {@code feedback.js}, the page again to a browser without it - and what
 * is checked before anything goes: the trap, the fields, the address's allowance. The Hub is a stand-in on a free
 * port, or not linked at all. The page's place behind the console's login is walked in {@code EndToEndTest}.
 */
class FeedbackControllerTest {

    private static final Principal OPERATOR = () -> "operator";

    private static MockMvcTester mvc(HubFeedback hub) {
        return mvc(hub, new FeedbackThrottle());
    }

    private static MockMvcTester mvc(HubFeedback hub, FeedbackThrottle throttle) {
        return MockMvcTester.create(MockMvcBuilders.standaloneSetup(new FeedbackController(hub, throttle))
                .setSingleView((model, request, response) -> { /* the template is rendered in EndToEndTest */ })
                .build());
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static MockMvcTester.MockMvcRequestBuilder script(MockMvcTester mvc) {
        return mvc.post().uri("/feedback").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_FORM_URLENCODED);
    }

    private static MockMvcTester.MockMvcRequestBuilder browser(MockMvcTester mvc) {
        return mvc.post().uri("/feedback").accept(MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML, MediaType.ALL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String body) {
        return JsonMapper.builder().build().readValue(body, LinkedHashMap.class);
    }

    @Test
    void thePageNamesTheAppTheSignedInUserAndTheSanitisedPage() {
        MockMvcTester mvc = mvc(StubHub.client(null));
        assertThat(mvc.get().uri("/feedback").param("from", "/console/upstreams?calls=all").principal(OPERATOR))
                .hasStatusOk().hasViewName("feedback")
                .model().containsEntry("feedbackApp", "Weather").containsEntry("feedbackName", "operator")
                .containsEntry("feedbackPage", "/console/upstreams?calls=all")
                .containsKey("feedbackEmail").extractingByKey("feedbackEmail").isNull();
        for (String elsewhere : new String[]{"https://evil.example/", "//evil.example/", "/\\evil.example", "console/map", ""}) {
            assertThat(mvc.get().uri("/feedback").param("from", elsewhere).principal(OPERATOR))
                    .as(elsewhere).hasStatusOk().model().extractingByKey("feedbackPage").isNull();
        }
        assertThat(mvc.get().uri("/feedback")).hasStatusOk().model().extractingByKey("feedbackName").isNull();
    }

    @Test
    void aSendTheHubQueuesAnswersSentWithTheAccountAndBrowserAdded() throws Exception {
        try (StubHub hub = new StubHub()) {
            MvcTestResult result = script(mvc(hub.client())).principal(OPERATOR).header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (test)")
                    .param("kind", "idea").param("message", "  Colour the map by gust.  ").param("name", "operator")
                    .param("email", "").param("page", "/console/map").param("website", "").exchange();
            assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .bodyJson().isStrictlyEqualTo("{\"sent\": true}");
            assertThat(hub.requests()).singleElement().satisfies(r -> assertThat(json(r.body())).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "kind", "idea", "message", "Colour the map by gust.", "name", "operator", "account", "operator",
                    "page", "/console/map", "userAgent", "Mozilla/5.0 (test)")));
        }
    }

    @Test
    void theTrapAnswersSentAndSendsNothing() throws Exception {
        try (StubHub hub = new StubHub()) {
            MockMvcTester mvc = mvc(hub.client());
            assertThat(script(mvc).param("message", "Buy cheap things").param("website", "https://spam.example"))
                    .hasStatusOk().bodyJson().isStrictlyEqualTo("{\"sent\": true}");
            // Even with nothing else filled in: a robot learns nothing from the answer.
            assertThat(script(mvc).param("website", "x")).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"sent\": true}");
            assertThat(browser(mvc).param("message", "Buy cheap things").param("website", "x"))
                    .hasStatusOk().hasViewName("feedback").model().containsEntry("feedbackSent", true);
            assertThat(hub.requests()).isEmpty();
        }
    }

    @Test
    void theirInputIsCheckedBeforeAnythingGoes() throws Exception {
        try (StubHub hub = new StubHub()) {
            MockMvcTester mvc = mvc(hub.client());
            assertThat(script(mvc).param("message", "   ")).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().isStrictlyEqualTo("{\"sent\": false, \"error\": \"Write a few words before sending.\"}");
            assertThat(script(mvc)).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(script(mvc).param("message", "x".repeat(4001))).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.error").asString().contains("4,000");
            assertThat(script(mvc).param("message", "hi").param("email", "not an address")).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.error").isEqualTo("That does not look like an email address.");
            assertThat(script(mvc).param("message", "hi").param("name", "n".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(hub.requests()).isEmpty();
            // A kind the Hub does not know goes as other; a page that is not a path here goes without one.
            assertThat(script(mvc).param("message", "hi").param("kind", "bug").param("page", "https://evil.example/")).hasStatusOk();
            assertThat(json(hub.requests().getFirst().body())).containsEntry("kind", "other").doesNotContainKey("page");
        }
    }

    @Test
    void theSixthSendInFifteenMinutesFromOneAddressIsRefused() throws Exception {
        try (StubHub hub = new StubHub()) {
            MockMvcTester mvc = mvc(hub.client());
            // Refused input does not use up the allowance.
            for (int i = 0; i < 6; i++) {
                assertThat(script(mvc).with(from("203.0.113.7")).param("message", "")).hasStatus(HttpStatus.BAD_REQUEST);
            }
            for (int i = 0; i < FeedbackThrottle.SENDS; i++) {
                assertThat(script(mvc).with(from("203.0.113.7")).param("message", "number " + i)).as("send " + (i + 1)).hasStatusOk();
            }
            assertThat(script(mvc).with(from("203.0.113.7")).param("message", "one more")).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .bodyJson().isStrictlyEqualTo("{\"sent\": false, \"error\": \"" + FeedbackController.TOO_MANY + "\"}");
            assertThat(browser(mvc).with(from("203.0.113.7")).param("message", "one more")).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .model().containsEntry("feedbackError", FeedbackController.TOO_MANY);
            assertThat(script(mvc).with(from("198.51.100.4")).param("message", "another address")).hasStatusOk();
            assertThat(hub.requests()).hasSize(FeedbackThrottle.SENDS + 1);
        }
    }

    @Test
    void theHubsAnswersAreToldAsTheReadmeHasThem() throws Exception {
        try (StubHub hub = new StubHub()) {
            MockMvcTester mvc = mvc(hub.client());
            hub.answersOutcome(200, "DEDUPED", null);
            assertThat(script(mvc).param("message", "again")).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"sent\": true}");
            hub.answersOutcome(200, "CAPPED", null);
            assertThat(script(mvc).param("message", "capped")).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .bodyJson().extractingPath("$.error").isEqualTo(FeedbackController.CAPPED);
            hub.answersOutcome(400, "INVALID", "message is required");
            assertThat(script(mvc).param("message", "invalid")).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.error").isEqualTo("message is required");
            hub.answersOutcome(400, "INVALID", null);
            assertThat(script(mvc).param("message", "invalid")).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.error").isEqualTo(FeedbackController.INVALID);
            hub.answersOutcome(503, "FAILED", "smtp down");
            assertThat(script(mvc).param("message", "failed")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .bodyJson().extractingPath("$.error").isEqualTo(FeedbackController.UNAVAILABLE);
        }
    }

    @Test
    void notLinkedAnswers503AndThePageKeepsWhatTheyWrote() {
        MockMvcTester mvc = mvc(StubHub.client(new au.gully.platform.GullyProperties.Hub("", "")));
        assertThat(script(mvc).param("message", "Nowhere to go")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().isStrictlyEqualTo("{\"sent\": false, \"error\": \"" + FeedbackController.UNAVAILABLE + "\"}");
        assertThat(browser(mvc).principal(OPERATOR).param("kind", "problem").param("message", "Nowhere to go")
                .param("name", "Jane").param("email", "jane@example.org").param("page", "/console/curing"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasViewName("feedback")
                .model().containsEntry("feedbackApp", "Weather").containsEntry("feedbackError", FeedbackController.UNAVAILABLE)
                .containsEntry("feedbackKind", "problem").containsEntry("feedbackMessage", "Nowhere to go")
                .containsEntry("feedbackName", "Jane").containsEntry("feedbackEmail", "jane@example.org")
                .containsEntry("feedbackPage", "/console/curing").doesNotContainKey("feedbackSent");
    }

    @Test
    void onlyAnAskForJsonIsAnsweredWithIt() throws Exception {
        try (StubHub hub = new StubHub()) {
            MockMvcTester mvc = mvc(hub.client());
            assertThat(browser(mvc).param("message", "from a form")).hasStatusOk().hasViewName("feedback")
                    .model().containsEntry("feedbackSent", true).containsEntry("feedbackApp", "Weather");
            assertThat(mvc.post().uri("/feedback").accept(MediaType.ALL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("message", "any answer")).hasStatusOk().hasViewName("feedback");
            assertThat(mvc.post().uri("/feedback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("message", "no accept")).hasStatusOk().hasViewName("feedback");
            assertThat(browser(mvc).param("message", "")).hasStatus(HttpStatus.BAD_REQUEST).hasViewName("feedback")
                    .model().containsEntry("feedbackError", "Write a few words before sending.");
        }
    }
}
