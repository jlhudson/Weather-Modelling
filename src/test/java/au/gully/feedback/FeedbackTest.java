package au.gully.feedback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The form's fields as the fleet's README holds them: trimmed, limited, the kind one of four, the page a path here.
 */
class FeedbackTest {

    private static Feedback form(String message, String name, String email) {
        return Feedback.of("problem", message, name, email, null, null, null);
    }

    @Test
    void theMessageIsRequiredAndAtMostFourThousandCharactersAfterTrimming() {
        assertThat(form(null, null, null).problem()).isEqualTo("Write a few words before sending.");
        assertThat(form("   \n\t ", null, null).problem()).isEqualTo("Write a few words before sending.");
        assertThat(form("  " + "x".repeat(4000) + "  ", null, null).problem()).isNull();
        assertThat(form("  " + "x".repeat(4000) + "  ", null, null).message()).hasSize(4000);
        assertThat(form("x".repeat(4001), null, null).problem()).isEqualTo("Your feedback is 4,001 characters: please keep it to 4,000.");
    }

    @Test
    void theNameAndEmailAreOptionalAndLimitedAndTheEmailMustLookLikeOne() {
        assertThat(form("hi", "", "  ").problem()).isNull();
        assertThat(form("hi", "", "  ").name()).isNull();
        assertThat(form("hi", "", "  ").email()).isNull();
        assertThat(form("hi", "n".repeat(100), null).problem()).isNull();
        assertThat(form("hi", "n".repeat(101), null).problem()).contains("name").contains("100");
        assertThat(form("hi", null, " jane@example.org ").problem()).isNull();
        assertThat(form("hi", null, " jane@example.org ").email()).isEqualTo("jane@example.org");
        for (String bad : new String[]{"jane", "jane@", "@example.org", "jane@example", "ja ne@example.org", "jane@@example.org"}) {
            assertThat(form("hi", null, bad).problem()).as(bad).isEqualTo("That does not look like an email address.");
        }
        String long200 = "j".repeat(200 - "@example.org".length()) + "@example.org";
        assertThat(form("hi", null, long200).problem()).isNull();
        assertThat(form("hi", null, "j" + long200).problem()).isEqualTo("That does not look like an email address.");
    }

    @Test
    void anyKindTheHubDoesNotKnowIsOther() {
        assertThat(Feedback.kind("problem")).isEqualTo("problem");
        assertThat(Feedback.kind(" IDEA ")).isEqualTo("idea");
        assertThat(Feedback.kind("praise")).isEqualTo("praise");
        assertThat(Feedback.kind("bug")).isEqualTo("other");
        assertThat(Feedback.kind("")).isEqualTo("other");
        assertThat(Feedback.kind(null)).isEqualTo("other");
    }

    @Test
    void thePageIsKeptOnlyWhenItIsAPathOnThisSite() {
        assertThat(Feedback.path("/console/map")).isEqualTo("/console/map");
        assertThat(Feedback.path(" /console/upstreams?calls=all ")).isEqualTo("/console/upstreams?calls=all");
        assertThat(Feedback.path("/")).isEqualTo("/");
        assertThat(Feedback.path("/" + "p".repeat(299))).hasSize(300);
        for (String dropped : new String[]{null, "", "   ", "console/map", "https://evil.example/", "//evil.example/x",
                "/\\evil.example", "javascript:alert(1)", "/console\r\nSet-Cookie: x=1", "/" + "p".repeat(300)}) {
            assertThat(Feedback.path(dropped)).as(String.valueOf(dropped)).isNull();
        }
    }

    @Test
    void theUserAgentIsCutToItsLimitAndTheAccountKept() {
        Feedback f = Feedback.of("idea", "hi", null, null, "operator", "/console/map", "Mozilla/5.0 " + "x".repeat(400));
        assertThat(f.userAgent()).hasSize(300).startsWith("Mozilla/5.0 ");
        assertThat(f.account()).isEqualTo("operator");
        assertThat(f.page()).isEqualTo("/console/map");
        assertThat(Feedback.of("idea", "hi", null, null, null, "https://evil.example", null).page()).isNull();
    }
}
