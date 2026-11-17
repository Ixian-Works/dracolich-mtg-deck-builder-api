package dm.dracolich.deckBuilder.core.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.response.DmdResponse;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for ai-api on a local port, speaking real HTTP, so the tests go through the same
 * WebClient, JSON mapping and SSE decoding as production.
 *
 * <p>Left alone it behaves like ai-api: {@code POST /agent/session} applies the two rules
 * {@code AgentServiceImpl.createSession} applies, {@code POST /agent/chat} streams two chunks, and
 * {@code GET /agent/session/{id}} returns one issue and one suggestion. A test can make the
 * session or the chat endpoint fail instead with {@link #sessionFails} or {@link #chatFails}.</p>
 */
final class StubAiApi implements AutoCloseable {

    static final String SESSION_ID = "session-1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;

    /** The body of every {@code POST /agent/session}, in order. */
    final List<JsonNode> sessionRequests = new CopyOnWriteArrayList<>();
    /** The body of every {@code POST /agent/chat}, in order. */
    final List<JsonNode> chatRequests = new CopyOnWriteArrayList<>();
    /** Every request line, such as {@code POST /agent/chat}, in order. */
    final List<String> requests = new CopyOnWriteArrayList<>();

    private volatile Failure sessionFailure;
    private volatile Failure chatFailure;

    private record Failure(int status, String contentType, String body) { }

    StubAiApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/session", this::session);
        server.createContext("/agent/chat", this::chat);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Makes {@code POST /agent/session} answer with ai-api's error envelope. */
    void sessionFails(int status, String code, String message) {
        sessionFailure = new Failure(status, "application/json", errorEnvelope(code, message));
    }

    /** Makes {@code POST /agent/chat} answer with ai-api's error envelope. */
    void chatFails(int status, String code, String message) {
        chatFailure = new Failure(status, "application/json", errorEnvelope(code, message));
    }

    /** Makes {@code POST /agent/chat} answer the way a proxy in front of ai-api would. */
    void chatFailsWithHtml(int status) {
        chatFailure = new Failure(status, "text/html", "<html><body>Bad Gateway</body></html>");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void session(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        if ("GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, "application/json", envelope("""
                    {"id": "%s", "session_type": "ANALYSIS",
                     "analysis_issues": [
                       {"topic": "removal_count", "severity": "WARNING", "reason": "Only one removal spell."}],
                     "card_suggestions": [
                       {"card_name": "Path to Exile", "reason": "Cheap removal.", "category": "removal",
                        "topic": "removal_count"}]}
                    """.formatted(SESSION_ID)));
            return;
        }

        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        sessionRequests.add(request);

        Failure failure = sessionFailure;
        if (failure != null) {
            respond(exchange, failure.status(), failure.contentType(), failure.body());
            return;
        }

        // The two rules of ai-api's AgentServiceImpl.createSession.
        String type = request.path("session_type").asString("");
        if ("BUILD".equals(type) && request.path("commander_name").asString("").isBlank()) {
            respond(exchange, 400, "application/json", errorEnvelope("DMD024",
                    "Validation error: commander_name is required for BUILD sessions"));
            return;
        }
        if (!"BUILD".equals(type) && request.path("deck_list").isEmpty()) {
            respond(exchange, 400, "application/json", errorEnvelope("DMD024",
                    "Validation error: deck_list is required for ANALYSIS sessions"));
            return;
        }
        respond(exchange, 200, "application/json",
                envelope("{\"id\": \"%s\", \"session_type\": \"%s\"}".formatted(SESSION_ID, type)));
    }

    private void chat(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        chatRequests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));

        Failure failure = chatFailure;
        if (failure != null) {
            respond(exchange, failure.status(), failure.contentType(), failure.body());
            return;
        }
        respond(exchange, 200, "text/event-stream", "data:A sturdy deck.\n\ndata:Add removal.\n\n");
    }

    // forge's envelope, written by Jackson 3 as Spring Boot 4 writes it. The status is an enum and
    // Jackson 3 writes enums with toString(), so it reads "200 OK" on the wire, not "OK".
    private static String envelope(String payload) {
        return JSON.writeValueAsString(
                DmdResponse.of(JSON.readTree(payload), HttpStatus.OK, "Request processed successfully"));
    }

    private static String errorEnvelope(String code, String message) {
        ApiError error = new ApiError();
        error.setError(code);
        return JSON.writeValueAsString(DmdResponse.of(List.of(error), HttpStatus.BAD_REQUEST, message));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
