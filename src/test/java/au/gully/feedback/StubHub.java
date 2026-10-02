package au.gully.feedback;

import au.gully.platform.GullyProperties;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the Hub's {@code POST /api/feedback} on a free port: it answers what it is told to and keeps every
 * request it was sent. Nothing here reaches the real Hub.
 */
public final class StubHub implements AutoCloseable {

    public static final String KEY = "weather_feedback-test-key-0123456789";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 202;
    private volatile String body = "{\"accepted\":true,\"outcome\":\"QUEUED\",\"reason\":null,\"generatedAt\":\"2026-10-03T00:00:00Z\"}";
    private volatile String contentType = "application/json";

    public StubHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Api-Key"), exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(in, StandardCharsets.UTF_8)));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    /**
     * A client as the application builds one, over the same Boot request factory.
     */
    public static HubFeedback client(GullyProperties.Hub hub) {
        return new HubFeedback(RestClient.builder(), ClientHttpRequestFactoryBuilder.detect(), HttpClientSettings.defaults(), hub);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public GullyProperties.Hub hub() {
        return new GullyProperties.Hub(url(), KEY);
    }

    public HubFeedback client() {
        return client(hub());
    }

    public StubHub answers(int status, String body) {
        return answers(status, body, "application/json");
    }

    public StubHub answers(int status, String body, String contentType) {
        this.status = status;
        this.body = body;
        this.contentType = contentType;
        return this;
    }

    public StubHub answersOutcome(int status, String outcome, String reason) {
        return answers(status, "{\"accepted\":" + (status < 300 && !"CAPPED".equals(outcome)) + ",\"outcome\":\"" + outcome
                + "\",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + ",\"generatedAt\":\"2026-10-03T00:00:00Z\"}");
    }

    public List<Request> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Request(String method, String path, String apiKey, String contentType, String body) {
    }
}
