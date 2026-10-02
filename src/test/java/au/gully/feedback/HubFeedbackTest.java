package au.gully.feedback;

import au.gully.platform.GullyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * The Hub's answers read as the README's table has them, against a stand-in Hub on a free port; and whenever the Hub
 * cannot take the message, the whole message in the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class HubFeedbackTest {

    private static final Feedback FEEDBACK = Feedback.of("problem", "The map is blank on my phone.", "Jane Citizen",
            "jane@example.org", "operator", "/console/map", "Mozilla/5.0 (test)");

    @Test
    void theMessageGoesAsJsonWithTheKeyToApiFeedbackAndQueuedIsSent() throws Exception {
        try (StubHub hub = new StubHub()) {
            HubFeedback.Result result = hub.client().send(FEEDBACK);
            assertThat(result.outcome()).isEqualTo(HubFeedback.Outcome.SENT);
            assertThat(hub.requests()).singleElement().satisfies(r -> {
                assertThat(r.method()).isEqualTo("POST");
                assertThat(r.path()).isEqualTo("/api/feedback");
                assertThat(r.apiKey()).isEqualTo(StubHub.KEY);
                assertThat(r.contentType()).startsWith("application/json");
                assertThat(json(r.body())).containsExactly(
                        entry("kind", "problem"),
                        entry("message", "The map is blank on my phone."),
                        entry("name", "Jane Citizen"),
                        entry("email", "jane@example.org"),
                        entry("account", "operator"),
                        entry("page", "/console/map"),
                        entry("userAgent", "Mozilla/5.0 (test)"));
            });
        }
    }

    @Test
    void theFieldsNotGivenAreLeftOut() throws Exception {
        try (StubHub hub = new StubHub()) {
            hub.client().send(Feedback.of(null, "Just this.", null, null, null, null, null));
            assertThat(json(hub.requests().getFirst().body())).containsExactly(entry("kind", "other"), entry("message", "Just this."));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String body) {
        return JsonMapper.builder().build().readValue(body, LinkedHashMap.class);
    }

    @Test
    void aTrailingSlashOnTheUrlIsNotDoubled() throws Exception {
        try (StubHub hub = new StubHub()) {
            StubHub.client(new GullyProperties.Hub(hub.url() + "/", " " + StubHub.KEY + " ")).send(FEEDBACK);
            assertThat(hub.requests()).singleElement().satisfies(r -> {
                assertThat(r.path()).isEqualTo("/api/feedback");
                assertThat(r.apiKey()).isEqualTo(StubHub.KEY);
            });
        }
    }

    @Test
    void dedupedIsSentCappedIsCappedAndInvalidCarriesTheReason(CapturedOutput output) throws Exception {
        try (StubHub hub = new StubHub()) {
            HubFeedback client = hub.client();
            hub.answersOutcome(200, "DEDUPED", null);
            assertThat(client.send(FEEDBACK).outcome()).isEqualTo(HubFeedback.Outcome.SENT);

            hub.answersOutcome(200, "CAPPED", "more than 20 in the hour");
            assertThat(client.send(FEEDBACK).outcome()).isEqualTo(HubFeedback.Outcome.CAPPED);

            hub.answersOutcome(400, "INVALID", "message is longer than 4000 characters");
            assertThat(client.send(FEEDBACK)).isEqualTo(new HubFeedback.Result(HubFeedback.Outcome.INVALID,
                    "message is longer than 4000 characters"));
            // None of these is a message lost.
            assertThat(output.getAll()).doesNotContain("feedback not sent to the Hub");
        }
    }

    @Test
    void everyOtherAnswerIsUnavailableAndTheWholeMessageIsLogged(CapturedOutput output) throws Exception {
        try (StubHub hub = new StubHub()) {
            HubFeedback client = hub.client();
            String[][] answers = {
                    {"503", "{\"accepted\":false,\"outcome\":\"UNCONFIGURED\",\"reason\":\"no mailbox\"}"},
                    {"503", "{\"accepted\":false,\"outcome\":\"FAILED\",\"reason\":\"smtp down\"}"},
                    {"500", "<html>oops</html>"},
                    {"502", ""},
                    {"401", "{\"status\":401,\"title\":\"Unauthorized\"}"},
                    {"202", "{\"accepted\":true,\"outcome\":\"SOMETHING_NEW\"}"},
                    {"200", "not json at all"}};
            for (String[] answer : answers) {
                hub.answers(Integer.parseInt(answer[0]), answer[1]);
                assertThat(client.send(FEEDBACK).outcome()).as(answer[0] + " " + answer[1]).isEqualTo(HubFeedback.Outcome.UNAVAILABLE);
            }
            assertThat(hub.requests()).hasSize(answers.length);
            assertThat(output.getAll()).contains("HTTP 503 UNCONFIGURED: no mailbox").contains("HTTP 503 FAILED: smtp down")
                    .contains("HTTP 500").contains("HTTP 401").contains("HTTP 202 SOMETHING_NEW")
                    .contains("The map is blank on my phone.").contains("jane@example.org").contains("/console/map");
        }
    }

    @Test
    void notLinkedSendsNothingAndLogsTheMessage(CapturedOutput output) throws Exception {
        try (StubHub hub = new StubHub()) {
            for (GullyProperties.Hub notLinked : new GullyProperties.Hub[]{null, new GullyProperties.Hub(null, null),
                    new GullyProperties.Hub("", StubHub.KEY), new GullyProperties.Hub(hub.url(), "  "), new GullyProperties.Hub(" ", null)}) {
                HubFeedback client = StubHub.client(notLinked);
                assertThat(client.linked()).isFalse();
                assertThat(client.send(FEEDBACK).outcome()).isEqualTo(HubFeedback.Outcome.UNAVAILABLE);
            }
            assertThat(hub.requests()).isEmpty();
            assertThat(output.getAll()).contains("WARN").contains("not linked: WEATHER_HUB_URL or WEATHER_HUB_API_KEY is blank")
                    .contains("The map is blank on my phone.");
            assertThat(hub.client().linked()).isTrue();
        }
    }

    @Test
    void aHubThatIsNotThereIsUnavailable(CapturedOutput output) throws Exception {
        String url;
        try (StubHub hub = new StubHub()) {
            url = hub.url();
        }
        HubFeedback client = StubHub.client(new GullyProperties.Hub(url, StubHub.KEY));
        assertThat(client.send(FEEDBACK).outcome()).isEqualTo(HubFeedback.Outcome.UNAVAILABLE);
        assertThat(output.getAll()).contains("unreachable: 127.0.0.1").contains("The map is blank on my phone.");
    }
}
