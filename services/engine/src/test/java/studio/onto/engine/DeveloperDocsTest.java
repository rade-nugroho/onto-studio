package studio.onto.engine;

import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperDocsTest {

    @Test
    void llmsTxtIsPublicPlainTextWithRootLinks() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response response = client.get("/llms.txt")) {
                assertEquals(200, response.code());
                String type = response.header("Content-Type");
                assertNotNull(type);
                assertTrue(type.toLowerCase(Locale.ROOT).startsWith("text/plain"), type);
                assertTrue(type.toLowerCase(Locale.ROOT).contains("charset=utf-8"), type);
                String body = response.body().string();
                assertTrue(body.startsWith("# "), body);
                assertFalse(body.startsWith("##"), body);
                assertTrue(body.contains("](/docs/intro.md)"), body);
                assertTrue(body.contains("](/openapi.json)"), body);
                assertTrue(body.contains("](/docs/example.md)"), body);
                assertTrue(body.contains("](/systemone/submit)"), body);
                assertTrue(body.contains("POST `/systemone/submit`"), body);
                assertTrue(body.contains("X-Engine-Token"), body);
                assertTrue(body.contains("KEYCLOAK_ISSUER"), body);
                assertTrue(body.contains("mapping.json"), body);
                assertFalse(body.contains("eyJ"), body);
            }
            assertEquals(401, client.get("/health").code());
        });
    }

    @Test
    void exampleUsesConnectorMappingFields() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            String example = body(client.get("/docs/example.md"));
            assertTrue(example.contains("/connectors/mapping/preview"), example);
            assertTrue(example.contains("/connectors/mapping/commit"), example);
            assertTrue(example.contains("/search"), example);
            assertTrue(example.contains("/entity"), example);
            assertTrue(example.contains("\"connectionName\""), example);
            assertTrue(example.contains("\"service\""), example);
            assertTrue(example.contains("\"actionId\""), example);
            assertTrue(example.contains("\"classIri\""), example);
            assertTrue(example.contains("\"idField\""), example);
            assertTrue(example.contains("\"fieldMap\""), example);
            assertTrue(example.contains("\"sampleLimit\""), example);
            String intro = body(client.get("/docs/intro.md"));
            assertTrue(intro.startsWith("# "), intro);
            assertTrue(intro.contains("X-Engine-Token"), intro);
            assertTrue(intro.contains("KEYCLOAK_ISSUER"), intro);
        });
    }

    private static String body(okhttp3.Response response) throws IOException {
        try (response) {
            assertEquals(200, response.code());
            return response.body().string();
        }
    }
}
