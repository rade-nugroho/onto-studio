package studio.onto.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorEngineTest {

    private static final Consumer<okhttp3.Request.Builder> HEADERS = builder -> builder
            .header("X-Engine-Token", "test")
            .header("Content-Type", "application/json");

    private static final String PIZZA = Path.of("src/test/resources/pizza.owl").toAbsolutePath().toString();

    private static final String[] ISOLATED_PROPERTIES = {
            "studio.onto.openConnectorUrl",
            "studio.onto.keycloakIssuer",
            "studio.onto.infisical.clientId",
            "studio.onto.infisical.clientSecret",
            "studio.onto.infisical.projectId"
    };

    private HttpServer gateway;
    private final Map<String, String> priorProperties = new LinkedHashMap<>();

    @BeforeEach
    void startGateway() throws Exception {
        for (String name : ISOLATED_PROPERTIES) {
            priorProperties.put(name, System.getProperty(name));
        }
        System.setProperty("studio.onto.keycloakIssuer", "");
        System.setProperty("studio.onto.infisical.clientId", "");
        System.setProperty("studio.onto.infisical.clientSecret", "");
        System.setProperty("studio.onto.infisical.projectId", "");
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", new StubGateway());
        gateway.start();
        System.setProperty("studio.onto.openConnectorUrl", "http://127.0.0.1:" + gateway.getAddress().getPort());
    }

    @AfterEach
    void stopGateway() {
        if (gateway != null) {
            gateway.stop(0);
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
    void catalogSearchFiltersProviders() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            String body = client.get("/connectors/apps?q=github", HEADERS).body().string();
            assertTrue(body.contains("github"), body);
            assertFalse(body.contains("slack"), body);
        });
    }

    @Test
    void readActionsExcludeWriteOperations() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            String body = client.get("/connectors/actions?app=github", HEADERS).body().string();
            assertTrue(body.contains("github.list_issues"), body);
            assertFalse(body.contains("github.create_issue"), body);
            assertFalse(body.contains("github.delete_repo"), body);
        });
    }

    @Test
    void previewDoesNotWriteMapping(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        Path mapping = copy.getParent().resolve(".onto-studio").resolve("mapping.json");

        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            String preview = body(client.post("/connectors/mapping/preview", """
                    {
                      "connectionName": "default",
                      "service": "github",
                      "actionId": "github.list_issues",
                      "classIri": "http://example.org#Issue",
                      "idField": "id",
                      "fieldMap": { "title": "http://example.org#title" },
                      "sampleLimit": 2
                    }
                    """, HEADERS));
            assertTrue(preview.contains("\"individuals\""), preview);
            assertEquals(false, Files.exists(mapping));
        });
    }

    @Test
    void commitWritesMappingAndEvidence(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        Path mapping = copy.getParent().resolve(".onto-studio").resolve("mapping.json");
        Path evidence = copy.getParent().resolve(".onto-studio").resolve("evidence.jsonl");

        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            String commit = body(client.post("/connectors/mapping/commit", """
                    {
                      "connectionName": "default",
                      "service": "github",
                      "actionId": "github.list_issues",
                      "classIri": "http://example.org#Issue",
                      "idField": "id",
                      "fieldMap": { "title": "http://example.org#title" },
                      "sampleLimit": 2
                    }
                    """, HEADERS));
            assertTrue(commit.contains("\"stored\":true"), commit);
            assertTrue(Files.isRegularFile(mapping), "mapping.json should exist");
            String mappingText = Files.readString(mapping);
            assertTrue(mappingText.contains("github.list_issues"), mappingText);
            String evidenceText = Files.readString(evidence);
            assertTrue(evidenceText.contains("connector-mapping"), evidenceText);
        });
    }

    @Test
    void missingGatewayReturnsServiceUnavailable() {
        System.setProperty("studio.onto.openConnectorUrl", "http://127.0.0.1:1");
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            int code = client.get("/connectors/apps", HEADERS).code();
            assertEquals(503, code);
            String body = client.get("/connectors/apps", HEADERS).body().string();
            assertTrue(body.contains("gatewayAvailable"), body);
            assertTrue(body.contains("error"), body);
            assertFalse(body.contains("Exception"), body);
        });
    }

    private static String body(okhttp3.Response response) throws IOException {
        try (response) {
            return response.body().string();
        }
    }

    private static final class StubGateway implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if ("/api/providers".equals(path)) {
                write(exchange, 200, """
                        {
                          "github": {
                            "name": "GitHub",
                            "auth": { "type": "oauth2" },
                            "locallyExecutable": true
                          },
                          "slack": {
                            "name": "Slack",
                            "auth": { "type": "oauth2" },
                            "locallyExecutable": true
                          }
                        }
                        """);
                return;
            }
            if ("/v1/connections".equals(path)) {
                write(exchange, 200, """
                        {
                          "success": true,
                          "message": "OK",
                          "data": [
                            {
                              "service": "github",
                              "alias": "default",
                              "authType": "oauth2",
                              "status": "active",
                              "appId": "app-1"
                            }
                          ],
                          "meta": {}
                        }
                        """);
                return;
            }
            if (path.startsWith("/v1/actions") && "GET".equals(exchange.getRequestMethod())) {
                if (query != null && query.contains("service=github")) {
                    write(exchange, 200, """
                            {
                              "success": true,
                              "message": "OK",
                              "data": [
                                {
                                  "id": "github.list_issues",
                                  "operationType": "read",
                                  "description": "List issues",
                                  "outputSchema": {
                                    "type": "object",
                                    "properties": { "id": { "type": "string" }, "title": { "type": "string" } }
                                  }
                                },
                                {
                                  "id": "github.create_issue",
                                  "operationType": "write",
                                  "description": "Create issue"
                                },
                                {
                                  "id": "github.delete_repo",
                                  "operationType": "destructive",
                                  "description": "Delete repo"
                                }
                              ],
                              "meta": {}
                            }
                            """);
                    return;
                }
            }
            if (path.startsWith("/v1/actions/") && "POST".equals(exchange.getRequestMethod())) {
                write(exchange, 200, """
                        {
                          "success": true,
                          "message": "OK",
                          "data": {
                            "items": [
                              { "id": "1", "title": "First issue" },
                              { "id": "2", "title": "Second issue" }
                            ]
                          },
                          "meta": {}
                        }
                        """);
                return;
            }
            write(exchange, 404, "{\"success\":false,\"message\":\"not found\"}");
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}