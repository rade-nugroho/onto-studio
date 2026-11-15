package studio.onto.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidecarEngineTest {

    private static final String PIZZA = Path.of("src/test/resources/pizza.owl").toAbsolutePath().toString();
    private static final String SENTINEL = "sentinel-secret-value";
    private static final String CONNECTOR_TOKEN = "connector-token-9f3a";
    private static final String CLIENT_SECRET = "client-secret-9f3a";
    private static final byte[] HMAC_SECRET = "test-hmac-secret-key-32bytes!!".getBytes(StandardCharsets.UTF_8);
    private static final String[] PROPERTIES = {
            "studio.onto.keycloakIssuer",
            "studio.onto.keycloakAudience",
            "studio.onto.infisical.clientId",
            "studio.onto.infisical.clientSecret",
            "studio.onto.infisical.projectId",
            "studio.onto.infisical.siteUrl",
            "studio.onto.infisical.environment",
            "studio.onto.openConnectorUrl",
            "studio.onto.openConnectorToken"
    };

    private static KeyPair keyPair;

    private final Map<String, String> prior = new LinkedHashMap<>();
    private final List<HttpServer> servers = new ArrayList<>();

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void isolateConfig() {
        for (String name : PROPERTIES) {
            prior.put(name, System.getProperty(name));
            System.setProperty(name, "");
        }
    }

    @AfterEach
    void restore() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        servers.clear();
        prior.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    void localTokenRemainsWhenIssuerUnset() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            assertEquals(401, client.get("/health").code());
            assertEquals(401, client.get("/openapi.json").code());
            assertEquals(200, client.get("/health", token()).code());
            String spec = body(client.get("/openapi.json", token()));
            assertTrue(spec.contains("\"/connectors/mapping/preview\""), spec);
            assertTrue(spec.contains("github.list_issues"), spec);
            assertTrue(spec.contains("X-Engine-Token"), spec);
            assertTrue(spec.contains("\"scheme\":\"bearer\""), spec);
            assertFalse(spec.contains(SENTINEL), spec);
            assertEquals(401, client.get("/search", bearer("not-a-jwt")).code());
        });
    }

    @Test
    void openApiOmitsConfiguredSecrets() {
        System.setProperty("studio.onto.infisical.clientId", "client-id");
        System.setProperty("studio.onto.infisical.clientSecret", SENTINEL);
        System.setProperty("studio.onto.infisical.projectId", "project-1");
        System.setProperty("studio.onto.infisical.siteUrl", "http://127.0.0.1:1");
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            String spec = body(client.get("/openapi.json", token()));
            assertFalse(spec.contains(SENTINEL), spec);
            assertTrue(spec.contains("\"connectionName\":\"default\""), spec);
            assertTrue(spec.contains("http://example.org#title"), spec);
        });
    }

    @Test
    void keycloakAcceptsRsaTokenAndRejectsBadTokens() throws Exception {
        AtomicInteger jwksHits = new AtomicInteger();
        String issuer = startIssuer(rsaJwks(), jwksHits);
        long soon = Instant.now().getEpochSecond() + 3600;
        String valid = rsaToken(issuer, "\"onto-studio\"", soon);
        String audienceList = rsaToken(issuer, "[\"onto-studio\",\"other\"]", soon);

        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            assertEquals(200, client.get("/health").code());
            assertEquals(200, client.get("/openapi.json").code());
            assertEquals(401, client.get("/search").code());
            assertEquals(401, client.get("/search", token()).code());
            assertEquals(409, client.get("/search", bearer(valid)).code());
            assertEquals(409, client.get("/search", bearer(audienceList)).code());
            assertEquals(1, jwksHits.get());
            assertEquals(401, client.get("/search", bearer(rsaToken(issuer, "\"other-api\"", soon))).code());
            assertEquals(401, client.get("/search", bearer(rsaToken(
                    "http://127.0.0.1:1/realms/other", "\"onto-studio\"", soon))).code());
            assertEquals(401, client.get("/search", bearer(rsaToken(
                    issuer, "\"onto-studio\"", Instant.now().getEpochSecond() - 120))).code());
            assertEquals(401, client.get("/search", bearer(tamperSignature(valid))).code());
            assertEquals(401, client.get("/search", bearer(
                    "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJ4In0.x")).code());
        });
    }

    @Test
    void keycloakAcceptsHmacToken() throws Exception {
        AtomicInteger jwksHits = new AtomicInteger();
        String issuer = startIssuer(hmacJwks(), jwksHits);
        String jwt = hmacToken(issuer, Instant.now().getEpochSecond() + 3600);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            assertEquals(409, client.get("/search", bearer(jwt)).code());
            assertEquals(1, jwksHits.get());
        });
    }

    @Test
    void infisicalTokenIsUsedAndNotPersisted(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> gatewayAuth = new AtomicReference<>();
        AtomicReference<String> secretQuery = new AtomicReference<>();
        HttpServer secrets = listen((exchange) -> infisical(exchange, secretQuery, 200, """
                {"accessToken":"infisical-access","expiresIn":7200,"accessTokenMaxTTL":43200,"tokenType":"Bearer"}
                """, """
                {"secret":{"secretKey":"OPEN_CONNECTOR_TOKEN","secretValue":"%s"}}
                """.formatted(CONNECTOR_TOKEN)));
        HttpServer gateway = listen((exchange) -> gateway(exchange, gatewayAuth));
        configureInfisical(secrets, gateway);

        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", token()));
            String apps = body(client.get("/connectors/apps", token()));
            assertFalse(apps.contains(CONNECTOR_TOKEN), apps);
            assertFalse(apps.contains(CLIENT_SECRET), apps);
            assertEquals("Bearer " + CONNECTOR_TOKEN, gatewayAuth.get());
            assertTrue(secretQuery.get().contains("projectId=project-1"), secretQuery.get());
            assertTrue(secretQuery.get().contains("environment=dev"), secretQuery.get());
            assertTrue(secretQuery.get().contains("secretPath=%2F"), secretQuery.get());

            body(client.post("/connectors/mapping/commit", mappingBody(), token()));
            String found = body(client.get("/search?q=1&kind=individual", token()));
            assertTrue(found.contains("github-1"), found);
            assertTrue(found.contains("\"kind\":\"individual\""), found);
            String owl = Files.readString(copy);
            assertFalse(owl.contains("First issue"), owl);
            assertFalse(owl.contains("github-1"), owl);
            String mapping = Files.readString(copy.getParent().resolve(".onto-studio/mapping.json"));
            assertTrue(mapping.contains("github.list_issues"), mapping);
            assertFalse(mapping.contains(CONNECTOR_TOKEN), mapping);
            assertFalse(mapping.contains(CLIENT_SECRET), mapping);
            String evidence = Files.readString(copy.getParent().resolve(".onto-studio/evidence.jsonl"));
            assertTrue(evidence.contains("connector-mapping"), evidence);
            assertFalse(evidence.contains(CONNECTOR_TOKEN), evidence);
            String spec = body(client.get("/openapi.json", token()));
            assertFalse(spec.contains(CONNECTOR_TOKEN), spec);
            assertFalse(spec.contains(CLIENT_SECRET), spec);
        });
    }

    @Test
    void infisicalFailureDoesNotLeakSecret() throws Exception {
        AtomicReference<String> gatewayAuth = new AtomicReference<>();
        HttpServer secrets = listen((exchange) -> {
            drain(exchange);
            write(exchange, 401, "{\"message\":\"denied\",\"echo\":\"" + CLIENT_SECRET + "\"}");
        });
        HttpServer gateway = listen((exchange) -> gateway(exchange, gatewayAuth));
        configureInfisical(secrets, gateway);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            try (okhttp3.Response response = client.get("/connectors/apps", token())) {
                assertEquals(503, response.code());
                String body = response.body().string();
                assertTrue(body.contains("Could not read connector credentials from Infisical"), body);
                assertFalse(body.contains(CLIENT_SECRET), body);
            }
            assertNull(gatewayAuth.get());
        });
    }

    @Test
    void unsetInfisicalKeepsEnvironmentToken() throws Exception {
        AtomicReference<String> gatewayAuth = new AtomicReference<>();
        HttpServer gateway = listen((exchange) -> gateway(exchange, gatewayAuth));
        System.setProperty("studio.onto.openConnectorUrl", "http://127.0.0.1:" + gateway.getAddress().getPort());
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            assertEquals(200, client.get("/connectors/apps", token()).code());
            assertNull(gatewayAuth.get());
        });
    }

    private void configureInfisical(HttpServer secrets, HttpServer gateway) {
        System.setProperty("studio.onto.infisical.clientId", "client-id");
        System.setProperty("studio.onto.infisical.clientSecret", CLIENT_SECRET);
        System.setProperty("studio.onto.infisical.projectId", "project-1");
        System.setProperty("studio.onto.infisical.siteUrl", "http://127.0.0.1:" + secrets.getAddress().getPort());
        System.setProperty("studio.onto.openConnectorUrl", "http://127.0.0.1:" + gateway.getAddress().getPort());
    }

    private String startIssuer(String jwks, AtomicInteger jwksHits) throws IOException {
        HttpServer server = listen((exchange) -> {
            String path = exchange.getRequestURI().getPath();
            drain(exchange);
            String issuer = "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/realms/onto";
            if (path.endsWith("/.well-known/openid-configuration")) {
                write(exchange, 200, "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer
                        + "/protocol/openid-connect/certs\"}");
                return;
            }
            if (path.endsWith("/protocol/openid-connect/certs")) {
                jwksHits.incrementAndGet();
                write(exchange, 200, jwks);
                return;
            }
            write(exchange, 404, "{}");
        });
        String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/onto";
        System.setProperty("studio.onto.keycloakIssuer", issuer);
        return issuer;
    }

    private HttpServer listen(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static void infisical(HttpExchange exchange, AtomicReference<String> secretQuery, int ignored,
                                  String loginBody, String secretBody) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        drain(exchange);
        if ("/api/v1/auth/universal-auth/login".equals(path) && "POST".equals(method)) {
            write(exchange, 200, loginBody);
            return;
        }
        if ("/api/v4/secrets/OPEN_CONNECTOR_TOKEN".equals(path) && "GET".equals(method)) {
            secretQuery.set(exchange.getRequestURI().getRawQuery());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer infisical-access".equals(authorization)) {
                write(exchange, 401, "{}");
                return;
            }
            write(exchange, 200, secretBody);
            return;
        }
        write(exchange, 404, "{}");
    }

    private static void gateway(HttpExchange exchange, AtomicReference<String> authorization) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null) {
            authorization.set(header);
        }
        String path = exchange.getRequestURI().getPath();
        drain(exchange);
        if ("/api/providers".equals(path)) {
            write(exchange, 200, """
                    {"github":{"name":"GitHub","auth":{"type":"oauth2"},"locallyExecutable":true}}
                    """);
            return;
        }
        if (path.startsWith("/v1/actions/") && "POST".equals(exchange.getRequestMethod())) {
            write(exchange, 200, """
                    {"success":true,"data":{"items":[{"id":"1","title":"First issue"},{"id":"2","title":"Second issue"}]}}
                    """);
            return;
        }
        write(exchange, 404, "{\"success\":false}");
    }

    private static String mappingBody() {
        return """
                {
                  "connectionName": "default",
                  "service": "github",
                  "actionId": "github.list_issues",
                  "classIri": "http://example.org#Issue",
                  "idField": "id",
                  "fieldMap": { "title": "http://example.org#title" },
                  "sampleLimit": 2
                }
                """;
    }

    private static String rsaJwks() {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-rsa\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + b64(unsigned(publicKey.getModulus())) + "\",\"e\":\""
                + b64(unsigned(publicKey.getPublicExponent())) + "\"}]}";
    }

    private static String hmacJwks() {
        return "{\"keys\":[{\"kty\":\"oct\",\"kid\":\"test-hmac\",\"use\":\"sig\",\"alg\":\"HS256\",\"k\":\""
                + b64(HMAC_SECRET) + "\"}]}";
    }

    private static String rsaToken(String issuer, String audienceJson, long expiresAt) throws Exception {
        return sign(issuer, audienceJson, expiresAt, "RS256", "test-rsa", (input) -> {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(input);
            return signature.sign();
        });
    }

    private static String hmacToken(String issuer, long expiresAt) throws Exception {
        return sign(issuer, "\"onto-studio\"", expiresAt, "HS256", "test-hmac", (input) -> {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_SECRET, "HmacSHA256"));
            return mac.doFinal(input);
        });
    }

    private static String sign(String issuer, String audienceJson, long expiresAt, String algorithm, String kid,
                               Signer signer) throws Exception {
        String header = b64(("{\"alg\":\"" + algorithm + "\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}")
                .getBytes(StandardCharsets.UTF_8));
        String payload = b64(("{\"iss\":\"" + issuer + "\",\"aud\":" + audienceJson + ",\"exp\":" + expiresAt + "}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        return signingInput + "." + b64(signer.sign(signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Flips the first signature character. The final base64url character can
     * sit entirely in unused padding bits, so changing only that character
     * leaves a valid token.
     */
    private static String tamperSignature(String token) {
        int index = token.lastIndexOf('.') + 1;
        char current = token.charAt(index);
        char replacement = current == 'A' ? 'B' : 'A';
        return token.substring(0, index) + replacement + token.substring(index + 1);
    }

    private static Consumer<okhttp3.Request.Builder> token() {
        return builder -> builder.header("X-Engine-Token", "test").header("Content-Type", "application/json");
    }

    private static Consumer<okhttp3.Request.Builder> bearer(String jwt) {
        return builder -> builder.header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json");
    }

    private static String body(okhttp3.Response response) throws IOException {
        try (response) {
            assertEquals(200, response.code(), response.toString());
            return response.body().string();
        }
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            input.readAllBytes();
        }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Signer {
        byte[] sign(byte[] input) throws Exception;
    }
}
