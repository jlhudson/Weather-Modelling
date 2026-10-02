package au.gully.console;

import au.gully.feedback.Feedback;
import au.gully.feedback.FeedbackThrottle;
import au.gully.feedback.HubFeedback;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The feedback page, the same in every application (The-Hub-Database/docs/feedback/README.md): a message for James,
 * sent through the Hub. Here it is a console page like the others, behind the console's sign-in, and the console's
 * user is its {@code account}.
 * <p>
 * One form, two answers: {@code feedback.js} asks for JSON and keeps the person on the page; a browser without it
 * posts the form and is answered with the page again, sent or with the error and what they wrote. Before anything
 * is sent the trap field is checked (filled, it answers as sent and sends nothing), then the fields, then the
 * address's allowance.
 */
@Controller
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    static final String APP = "Weather";
    static final String TOO_MANY = "You have sent a lot of feedback in a short time: please wait a few minutes and try again.";
    static final String CAPPED = "There has been a lot of feedback just now: please try again in an hour.";
    static final String INVALID = "Your feedback could not be sent as it is: please check it and try again.";
    static final String UNAVAILABLE = "Your feedback could not be sent just now. Please try again in a few minutes.";

    private final HubFeedback hub;
    private final FeedbackThrottle throttle;

    private static String signedIn(Principal principal) {
        return principal == null || principal.getName() == null || principal.getName().isBlank() ? null : principal.getName();
    }

    @GetMapping
    public String page(@RequestParam(required = false) String from, Principal principal, Model model) {
        model.addAttribute("feedbackApp", APP);
        model.addAttribute("feedbackName", signedIn(principal));
        model.addAttribute("feedbackEmail", null);
        model.addAttribute("feedbackPage", Feedback.path(from));
        return "feedback";
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sendForScript(Form form, Principal principal, HttpServletRequest request) {
        Answer answer = send(form, principal, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sent", answer.sent());
        if (!answer.sent()) {
            body.put("error", answer.error());
        }
        return ResponseEntity.status(answer.status()).body(body);
    }

    @PostMapping
    public ModelAndView sendForPage(Form form, Principal principal, HttpServletRequest request) {
        Answer answer = send(form, principal, request);
        ModelAndView page = new ModelAndView("feedback");
        page.setStatus(answer.status());
        page.addObject("feedbackApp", APP);
        page.addObject("feedbackPage", Feedback.path(form.page()));
        if (answer.sent()) {
            page.addObject("feedbackSent", true);
        } else {
            page.addObject("feedbackError", answer.error());
            page.addObject("feedbackKind", form.kind() == null ? null : Feedback.kind(form.kind()));
            page.addObject("feedbackMessage", form.message());
            page.addObject("feedbackName", form.name());
            page.addObject("feedbackEmail", form.email());
        }
        return page;
    }

    private Answer send(Form form, Principal principal, HttpServletRequest request) {
        if (form.website() != null && !form.website().isBlank()) {
            return Answer.SENT;
        }
        Feedback feedback = Feedback.of(form.kind(), form.message(), form.name(), form.email(), signedIn(principal),
                form.page(), request.getHeader(HttpHeaders.USER_AGENT));
        String problem = feedback.problem();
        if (problem != null) {
            return new Answer(HttpStatus.BAD_REQUEST, problem);
        }
        if (!throttle.take(request.getRemoteAddr())) {
            return new Answer(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
        }
        HubFeedback.Result result = hub.send(feedback);
        return switch (result.outcome()) {
            case SENT -> Answer.SENT;
            case CAPPED -> new Answer(HttpStatus.TOO_MANY_REQUESTS, CAPPED);
            case INVALID -> new Answer(HttpStatus.BAD_REQUEST,
                    result.reason() == null || result.reason().isBlank() ? INVALID : result.reason());
            case UNAVAILABLE -> new Answer(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        };
    }

    /**
     * The form's fields as posted, every one optional to the binder: {@link Feedback#problem()} says what is missing.
     *
     * @param website the trap: hidden from people, filled in by robots
     */
    public record Form(String kind, String message, String name, String email, String page, String website) {
    }

    /**
     * @param error the sentence for the person, when not sent
     */
    record Answer(HttpStatus status, String error) {

        static final Answer SENT = new Answer(HttpStatus.OK, null);

        boolean sent() {
            return status == HttpStatus.OK;
        }
    }
}
