package studio.onto.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemOneTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "sk-or-v1-test-key-not-a-real-secret";
    private static final Consumer<okhttp3.Request.Builder> HEADERS = builder -> builder
            .header("X-Engine-Token", "test")
            .header("Content-Type", "application/json");

    private static final String REQUEST = """
            {
              "model": "typesafe/jev-1.13",
              "session_id": "session-1234",
              "user": "onto-studio",
              "state": "My checkout page shows a blank screen after I click Pay.",
              "questions": {
                "is_bug": {
                  "type": "noul",
                  "instructions": "Is the customer reporting a software defect?",
                  "criteria": {
                    "true": "The customer describes broken or unexpected product behavior.",
                    "false": "The customer is asking a question or requesting a feature."
                  }
                }
              }
            }
            """;

    private static final String SUCCESS = """
            {
              "id": "gen-dec-1789738314-X5e5eKGQdvR9rblyX250",
              "model": "typesafe/jev-1.13-20260917",
              "provider": "TypeSafe",
              "answers": {
                "is_bug": { "type": "noul", "noul": 0.96 }
              },
              "usage": { "input_tokens": 476, "output_tokens": 70, "cost": 0.000019992 }
            }
            """;

    private HttpServer stub;
    private StubHandler handler;
    private final Map<String, String> priorProperties = new LinkedHashMap<>();

    @BeforeEach
    void startStub() throws Exception {
        save("studio.onto.keycloakIssuer");
        save(SystemOneClient.API_KEY_PROPERTY);
        save(SystemOneClient.BASE_URL_PROPERTY);
        System.setProperty("studio.onto.keycloakIssuer", "");
        System.setProperty(SystemOneClient.API_KEY_PROPERTY, API_KEY);
        handler = new StubHandler();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/systemone", handler);
        stub.start();
        System.setProperty(SystemOneClient.BASE_URL_PROPERTY,
                "http://127.0.0.1:" + stub.getAddress().getPort());
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
        System.setProperty(SystemOneClient.API_KEY_PROPERTY, "");
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response denied = client.post("/systemone/submit", REQUEST,
                    builder -> builder.header("Content-Type", "application/json"))) {
                assertEquals(401, denied.code());
            }
            try (okhttp3.Response response = client.post("/systemone/submit", REQUEST, HEADERS)) {
                assertEquals(503, response.code());
                String type = response.header("Content-Type");
                assertTrue(type != null && type.contains("application/json"), type);
                String body = response.body().string();
                assertTrue(body.contains("\"error\""), body);
                assertTrue(body.contains("OPENROUTER_API_KEY"), body);
                assertFalse(body.contains(API_KEY), body);
                assertFalse(body.contains("Exception"), body);
                assertEquals(0, handler.calls.get());
            }
        });
    }

    @Test
    void submitForwardsRequestAndReturnsAnswers() throws Exception {
        handler.mode = Mode.SUCCESS;
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response response = client.post("/systemone/submit", REQUEST, HEADERS)) {
                assertEquals(200, response.code());
                String type = response.header("Content-Type");
                assertTrue(type != null && type.contains("application/json"), type);
                String body = response.body().string();
                assertFalse(body.contains(API_KEY), body);
                JsonNode answers = MAPPER.readTree(body);
                assertEquals("gen-dec-1789738314-X5e5eKGQdvR9rblyX250", answers.path("id").asText());
                assertEquals("typesafe/jev-1.13-20260917", answers.path("model").asText());
                assertEquals("TypeSafe", answers.path("provider").asText());
                assertEquals("noul", answers.path("answers").path("is_bug").path("type").asText());
                assertEquals(0.96, answers.path("answers").path("is_bug").path("noul").asDouble());
                assertEquals(476, answers.path("usage").path("input_tokens").asInt());
                assertEquals(70, answers.path("usage").path("output_tokens").asInt());
                assertEquals(0.000019992, answers.path("usage").path("cost").asDouble());
            }
            assertEquals(1, handler.calls.get());
            assertEquals("POST", handler.method);
            assertEquals("/systemone", handler.path);
            assertEquals("Bearer " + API_KEY, handler.authorization);
            assertEquals("application/json", handler.contentType);
            assertNull(handler.engineToken);
            JsonNode forwarded = MAPPER.readTree(handler.body);
            assertEquals("typesafe/jev-1.13", forwarded.path("model").asText());
            assertEquals("session-1234", forwarded.path("session_id").asText());
            assertEquals("onto-studio", forwarded.path("user").asText());
            assertEquals("My checkout page shows a blank screen after I click Pay.", forwarded.path("state").asText());
            assertEquals("noul", forwarded.path("questions").path("is_bug").path("type").asText());
            assertEquals("Is the customer reporting a software defect?",
                    forwarded.path("questions").path("is_bug").path("instructions").asText());
            assertTrue(forwarded.path("questions").path("is_bug").path("criteria").has("true"));
            assertTrue(forwarded.path("questions").path("is_bug").path("criteria").has("false"));
        });
    }

    @Test
    void upstreamErrorBecomesJsonError() {
        handler.mode = Mode.ERROR;
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response response = client.post("/systemone/submit", REQUEST, HEADERS)) {
                assertEquals(502, response.code());
                String type = response.header("Content-Type");
                assertTrue(type != null && type.contains("application/json"), type);
                String body = response.body().string();
                assertTrue(body.contains("\"error\""), body);
                assertTrue(body.contains("Invalid request parameters"), body);
                assertTrue(body.contains("400"), body);
                assertFalse(body.contains(API_KEY), body);
                assertFalse(body.contains("Exception"), body);
                assertFalse(body.contains("\tat "), body);
            }
            assertEquals(1, handler.calls.get());
        });
    }

    private void save(String name) {
        priorProperties.put(name, System.getProperty(name));
    }

    private enum Mode {
        SUCCESS,
        ERROR
    }

    private static final class StubHandler implements com.sun.net.httpserver.HttpHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Mode mode = Mode.SUCCESS;
        private volatile String method;
        private volatile String path;
        private volatile String authorization;
        private volatile String contentType;
        private volatile String engineToken;
        private volatile String body;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            engineToken = exchange.getRequestHeaders().getFirst("X-Engine-Token");
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.incrementAndGet();
            if (mode == Mode.ERROR) {
                write(exchange, 400, """
                        {"error":{"code":400,"message":"Invalid request parameters"}}
                        """);
                return;
            }
            write(exchange, 200, SUCCESS);
        }

        private static void write(HttpExchange exchange, int status, String payload) throws IOException {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
