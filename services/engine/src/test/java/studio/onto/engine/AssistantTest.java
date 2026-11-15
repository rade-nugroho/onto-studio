package studio.onto.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AssistantTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "sk-or-v1-test-key-not-a-real-secret";
    private static final String MODEL = "deepseek/deepseek-v4.1-flash-1017";
    private static final String NS = "http://example.invalid/ontologies/pizza/2005/10/18/pizza.owl#";
    private static final Consumer<okhttp3.Request.Builder> HEADERS = builder -> builder
            .header("X-Engine-Token", "test")
            .header("Content-Type", "application/json");

    private HttpServer stub;
    private StubHandler handler;
    private final Map<String, String> priorProperties = new LinkedHashMap<>();

    @BeforeEach
    void startStub() throws Exception {
        save(Assistant.API_KEY_PROPERTY);
        save(Assistant.BASE_URL_PROPERTY);
        save("studio.onto.disableEnvFile");
        save("studio.onto.keycloakIssuer");
        System.setProperty(Assistant.API_KEY_PROPERTY, API_KEY);
        System.setProperty("studio.onto.disableEnvFile", "true");
        System.setProperty("studio.onto.keycloakIssuer", "");
        handler = new StubHandler();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/chat/completions", handler);
        stub.start();
        System.setProperty(Assistant.BASE_URL_PROPERTY, "http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
        priorProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    void missingApiKeyReturnsJsonError() {
        System.setProperty(Assistant.API_KEY_PROPERTY, "");
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response response = client.post("/assistant", "{\"question\":\"hi\"}", HEADERS)) {
                assertEquals(503, response.code());
                String type = response.header("Content-Type");
                assertTrue(type != null && type.contains("application/json"), type);
                String body = response.body().string();
                assertTrue(body.contains("\"error\""), body);
                assertTrue(body.contains("OPENROUTER_API_KEY"), body);
                assertFalse(body.contains("ANTHROPIC"), body);
                assertFalse(body.contains(API_KEY), body);
            }
            assertEquals(0, handler.calls.get());
        });
    }

    @Test
    void stubReplyMapsToAssistantAnswer() throws Exception {
        handler.enqueue(200, completion("stop", "Pizza is a named class.", List.of()));
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response response = client.post("/assistant",
                    "{\"question\":\"What is Pizza?\"}", HEADERS)) {
                assertEquals(200, response.code());
                String type = response.header("Content-Type");
                assertTrue(type != null && type.contains("application/json"), type);
                JsonNode body = MAPPER.readTree(response.body().string());
                assertEquals("Pizza is a named class.", body.path("answer").asText());
                assertEquals(1, body.size());
                assertFalse(body.toString().contains(API_KEY));
            }
            assertEquals(1, handler.calls.get());
            Captured request = handler.captured.get(0);
            assertEquals("POST", request.method);
            assertEquals("/chat/completions", request.path);
            assertEquals("Bearer " + API_KEY, request.authorization);
            assertEquals("application/json", request.contentType);
            assertEquals("application/json", request.accept);
            assertNull(request.referer);
            assertNull(request.title);
            assertNull(request.engineToken);
            JsonNode sent = MAPPER.readTree(request.body);
            assertEquals(MODEL, sent.path("model").asText());
            assertTrue(sent.path("stream").isBoolean() && !sent.path("stream").asBoolean());
            assertTrue(sent.path("messages").toString().contains("What is Pizza?"), sent.toString());
            assertTrue(sent.path("tools").toString().contains("ontology_add_subclass"), sent.toString());
            assertFalse(request.body.contains(API_KEY), request.body);
            assertFalse(request.body.contains("anthropic"), request.body);
        });
    }

    @Test
    void upstreamErrorBecomesJsonError() {
        handler.enqueue(401, "{\"error\":{\"message\":\"rejected " + API_KEY + "\"}}");
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response response = client.post("/assistant", "{\"question\":\"hi\"}", HEADERS)) {
                assertEquals(502, response.code());
                String type = response.header("Content-Type");
                assertTrue(type != null && type.contains("application/json"), type);
                String body = response.body().string();
                assertTrue(body.contains("\"error\""), body);
                assertTrue(body.contains("OpenRouter returned 401"), body);
                assertTrue(body.contains("[redacted]"), body);
                assertFalse(body.contains(API_KEY), body);
                assertFalse(body.contains("Exception"), body);
                assertFalse(body.contains("\tat "), body);
            }
            assertEquals(1, handler.calls.get());
        });
    }

    @Test
    void toolCallEditsThroughEngineAndRecordsEvidence(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of("src/test/resources/pizza.owl"), copy);
        String sub = NS + "Margherita";
        String sup = NS + "Pizza";
        String arguments = MAPPER.writeValueAsString(Map.of("sub", sub, "sup", sup));
        handler.enqueue(200, completion("tool_calls", null, List.of(Map.of(
                "id", "call-1",
                "type", "function",
                "function", Map.of("name", "ontology_add_subclass", "arguments", arguments)))));
        handler.enqueue(200, completion("stop", "Added Margherita as a subclass of Pizza.", List.of()));

        Workspace workspace = new Workspace();
        Assistant assistant = new Assistant(workspace);
        JavalinTest.test(Main.createApp(workspace, "test", assistant), (server, client) -> {
            String openBody = MAPPER.writeValueAsString(Map.of("path", copy.toString()));
            assertEquals(200, client.post("/ontology/open", openBody, HEADERS).code());
            assistant.setEndpoint("http://127.0.0.1:" + server.port(), "test");
            byte[] before = Files.readAllBytes(copy);

            try (okhttp3.Response response = client.post("/assistant",
                    "{\"question\":\"Make Margherita a subclass of Pizza\"}", HEADERS)) {
                assertEquals(200, response.code(), response.peekBody(2000).string());
                JsonNode body = MAPPER.readTree(response.body().string());
                assertEquals("Added Margherita as a subclass of Pizza.", body.path("answer").asText());
                assertFalse(body.toString().contains(API_KEY));
            }
            assertArrayEquals(before, Files.readAllBytes(copy));

            String entity = client.get("/entity?iri=" + encoded("Margherita"), HEADERS).body().string();
            assertTrue(entity.contains("Margherita ⊑ Pizza"), entity);
            String evidence = client.get("/evidence?iri=" + encoded("Margherita"), HEADERS).body().string();
            assertTrue(evidence.contains("\"source\":\"manual\""), evidence);
            assertFalse(evidence.contains(API_KEY), evidence);
            String undo = client.post("/undo", "", HEADERS).body().string();
            assertTrue(undo.contains("\"canRedo\":true"), undo);
            String undone = client.get("/entity?iri=" + encoded("Margherita"), HEADERS).body().string();
            assertFalse(undone.contains("Margherita ⊑ Pizza"), undone);

            assertEquals(2, handler.calls.get());
            JsonNode followUp = MAPPER.readTree(handler.captured.get(1).body);
            boolean foundToolResult = false;
            for (JsonNode message : followUp.path("messages")) {
                if ("tool".equals(message.path("role").asText())
                        && "call-1".equals(message.path("tool_call_id").asText())) {
                    foundToolResult = true;
                    assertTrue(message.path("content").asText().contains("canUndo"), message.toString());
                }
            }
            assertTrue(foundToolResult, followUp.toString());
            assertEquals(MODEL, followUp.path("model").asText());
        });
    }

    private void save(String name) {
        priorProperties.put(name, System.getProperty(name));
    }

    private static String encoded(String label) throws Exception {
        return java.net.URLEncoder.encode(NS + label, StandardCharsets.UTF_8);
    }

    private static String completion(String finishReason, String content, List<Map<String, Object>> toolCalls)
            throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        if (!toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("finish_reason", finishReason);
        choice.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "gen-test");
        body.put("object", "chat.completion");
        body.put("created", 1);
        body.put("model", MODEL);
        body.put("choices", List.of(choice));
        return MAPPER.writeValueAsString(body);
    }

    private static final class Captured {
        private final String method;
        private final String path;
        private final String authorization;
        private final String contentType;
        private final String accept;
        private final String referer;
        private final String title;
        private final String engineToken;
        private final String body;

        private Captured(HttpExchange exchange, String body) {
            this.method = exchange.getRequestMethod();
            this.path = exchange.getRequestURI().getPath();
            this.authorization = exchange.getRequestHeaders().getFirst("Authorization");
            this.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            this.accept = exchange.getRequestHeaders().getFirst("Accept");
            this.referer = exchange.getRequestHeaders().getFirst("HTTP-Referer");
            this.title = exchange.getRequestHeaders().getFirst("X-Title");
            this.engineToken = exchange.getRequestHeaders().getFirst("X-Engine-Token");
            this.body = body;
        }
    }

    private static final class StubHandler implements com.sun.net.httpserver.HttpHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Captured> captured = new ArrayList<>();
        private final List<Script> scripts = new ArrayList<>();

        private void enqueue(int status, String body) {
            scripts.add(new Script(status, body));
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int index = calls.getAndIncrement();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new Captured(exchange, requestBody));
            Script script = index < scripts.size()
                    ? scripts.get(index)
                    : new Script(500, "{\"error\":{\"message\":\"unexpected extra call\"}}");
            byte[] bytes = script.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(script.status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private record Script(int status, String body) {
    }
}
