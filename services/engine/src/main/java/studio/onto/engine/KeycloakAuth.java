package studio.onto.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates access tokens Keycloak has already issued. Adaptive checks (device,
 * risk, step-up) stay in the Keycloak extension; this class only checks the
 * resulting JWT against the issuer's published keys.
 */
final class KeycloakAuth {

    static final String ISSUER_PROPERTY = "studio.onto.keycloakIssuer";
    static final String AUDIENCE_PROPERTY = "studio.onto.keycloakAudience";
    static final String DEFAULT_AUDIENCE = "onto-studio";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long CACHE_TTL_MILLIS = 60_000L;
    private static final long CLOCK_SKEW_SECONDS = 30L;
    private static final int MAX_TOKEN_CHARS = 64 * 1024;
    private static final int MAX_BODY_CHARS = 1_000_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private volatile Cache cache;

    boolean enabled() {
        return issuer() != null;
    }

    void requireBearer(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedResponse();
        }
        String token = header.substring(7).trim();
        if (token.isEmpty() || token.indexOf(' ') >= 0 || token.length() > MAX_TOKEN_CHARS) {
            throw new UnauthorizedResponse();
        }
        try {
            verify(token);
        } catch (AuthFailure ignored) {
            throw new UnauthorizedResponse();
        }
    }

    private void verify(String token) throws AuthFailure {
        String issuer = issuer();
        if (issuer == null || !httpIssuer(issuer)) {
            throw new AuthFailure();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new AuthFailure();
        }
        JsonNode header = readJson(base64Url(parts[0]));
        if (!header.isObject()) {
            throw new AuthFailure();
        }
        String algorithm = header.path("alg").asText("");
        String kid = header.path("kid").asText("");
        if (kid.isEmpty() || "none".equalsIgnoreCase(algorithm)) {
            throw new AuthFailure();
        }
        JsonNode jwk = keyFor(issuer, kid);
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        byte[] signature = base64Url(parts[2]);
        if (!signatureMatches(algorithm, jwk, signingInput, signature)) {
            throw new AuthFailure();
        }
        JsonNode payload = readJson(base64Url(parts[1]));
        if (!payload.isObject()) {
            throw new AuthFailure();
        }
        checkClaims(payload, issuer);
    }

    private void checkClaims(JsonNode payload, String issuer) throws AuthFailure {
        if (!issuer.equals(payload.path("iss").asText())) {
            throw new AuthFailure();
        }
        if (!audienceMatches(payload.get("aud"), audience())) {
            throw new AuthFailure();
        }
        JsonNode expires = payload.get("exp");
        if (expires == null || !expires.isNumber()) {
            throw new AuthFailure();
        }
        long now = Instant.now().getEpochSecond();
        if (now > expires.asLong() + CLOCK_SKEW_SECONDS) {
            throw new AuthFailure();
        }
        JsonNode notBefore = payload.get("nbf");
        if (notBefore != null && !notBefore.isNull()) {
            if (!notBefore.isNumber() || now + CLOCK_SKEW_SECONDS < notBefore.asLong()) {
                throw new AuthFailure();
            }
        }
    }

    private JsonNode keyFor(String issuer, String kid) throws AuthFailure {
        JsonNode found = keys(issuer, false).get(kid);
        if (found != null) {
            return found;
        }
        found = keys(issuer, true).get(kid);
        if (found == null) {
            throw new AuthFailure();
        }
        return found;
    }

    private Map<String, JsonNode> keys(String issuer, boolean force) throws AuthFailure {
        long now = System.currentTimeMillis();
        Cache current = cache;
        if (!force && current != null && issuer.equals(current.issuer) && now < current.expiresAtMillis) {
            return current.keys;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            current = cache;
            if (!force && current != null && issuer.equals(current.issuer) && now < current.expiresAtMillis) {
                return current.keys;
            }
            Map<String, JsonNode> fresh = fetchKeys(issuer);
            cache = new Cache(issuer, fresh, System.currentTimeMillis() + CACHE_TTL_MILLIS);
            return fresh;
        }
    }

    private Map<String, JsonNode> fetchKeys(String issuer) throws AuthFailure {
        JsonNode discovery = getJson(discoveryUrl(issuer));
        if (!issuer.equals(discovery.path("issuer").asText())) {
            throw new AuthFailure();
        }
        String jwksUri = discovery.path("jwks_uri").asText("");
        if (!sameAuthority(issuer, jwksUri)) {
            throw new AuthFailure();
        }
        JsonNode jwks = getJson(jwksUri);
        JsonNode keys = jwks.path("keys");
        if (!keys.isArray()) {
            throw new AuthFailure();
        }
        Map<String, JsonNode> byKid = new LinkedHashMap<>();
        for (JsonNode key : keys) {
            String kid = key.path("kid").asText("");
            if (!kid.isEmpty()) {
                byKid.putIfAbsent(kid, key);
            }
        }
        return byKid;
    }

    private JsonNode getJson(String url) throws AuthFailure {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AuthFailure();
            }
            String body = response.body();
            if (body == null || body.length() > MAX_BODY_CHARS) {
                throw new AuthFailure();
            }
            return readJson(body.getBytes(StandardCharsets.UTF_8));
        } catch (AuthFailure error) {
            throw error;
        } catch (IOException | InterruptedException | IllegalArgumentException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthFailure();
        }
    }

    private static boolean signatureMatches(String algorithm, JsonNode jwk, byte[] signingInput, byte[] signature)
            throws AuthFailure {
        String use = jwk.path("use").asText("");
        if (!use.isEmpty() && !"sig".equals(use)) {
            throw new AuthFailure();
        }
        String keyType = jwk.path("kty").asText("");
        try {
            if ("RS256".equals(algorithm) && "RSA".equals(keyType)) {
                return verifyRsa(jwk, signingInput, signature);
            }
            if ("HS256".equals(algorithm) && "oct".equals(keyType)) {
                return verifyHmac(jwk, signingInput, signature);
            }
        } catch (GeneralSecurityException error) {
            throw new AuthFailure();
        }
        throw new AuthFailure();
    }

    private static boolean verifyRsa(JsonNode jwk, byte[] signingInput, byte[] signature)
            throws GeneralSecurityException, AuthFailure {
        String modulus = jwk.path("n").asText("");
        String exponent = jwk.path("e").asText("");
        if (modulus.isEmpty() || exponent.isEmpty()) {
            throw new AuthFailure();
        }
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, base64Url(modulus)),
                new BigInteger(1, base64Url(exponent))));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(signingInput);
        return verifier.verify(signature);
    }

    private static boolean verifyHmac(JsonNode jwk, byte[] signingInput, byte[] signature)
            throws GeneralSecurityException, AuthFailure {
        String encoded = jwk.path("k").asText("");
        if (encoded.isEmpty()) {
            throw new AuthFailure();
        }
        byte[] secret = base64Url(encoded);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] expected = mac.doFinal(signingInput);
        return expected.length == signature.length && MessageDigest.isEqual(expected, signature);
    }

    private static boolean audienceMatches(JsonNode audience, String expected) {
        if (audience == null || audience.isNull()) {
            return false;
        }
        if (audience.isTextual()) {
            return expected.equals(audience.asText());
        }
        if (audience.isArray()) {
            for (JsonNode item : audience) {
                if (expected.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String issuer() {
        return RuntimeConfig.get(ISSUER_PROPERTY, "KEYCLOAK_ISSUER");
    }

    private static String audience() {
        String configured = RuntimeConfig.get(AUDIENCE_PROPERTY, "KEYCLOAK_AUDIENCE");
        return configured == null ? DEFAULT_AUDIENCE : configured;
    }

    private static String discoveryUrl(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        return base + "/.well-known/openid-configuration";
    }

    private static boolean httpIssuer(String issuer) {
        try {
            return sameAuthority(issuer, issuer);
        } catch (AuthFailure error) {
            return false;
        }
    }

    private static boolean sameAuthority(String left, String right) throws AuthFailure {
        URI leftUri = parseHttp(left);
        URI rightUri = parseHttp(right);
        return leftUri.getHost().equalsIgnoreCase(rightUri.getHost())
                && effectivePort(leftUri) == effectivePort(rightUri);
    }

    private static URI parseHttp(String value) throws AuthFailure {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new AuthFailure();
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new AuthFailure();
        }
        return uri;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static JsonNode readJson(byte[] bytes) throws AuthFailure {
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException error) {
            throw new AuthFailure();
        }
    }

    private static byte[] base64Url(String value) throws AuthFailure {
        int remainder = value.length() % 4;
        String padded = remainder == 0 ? value : value + "====".substring(remainder);
        try {
            return Base64.getUrlDecoder().decode(padded);
        } catch (IllegalArgumentException error) {
            throw new AuthFailure();
        }
    }

    private static final class Cache {
        private final String issuer;
        private final Map<String, JsonNode> keys;
        private final long expiresAtMillis;

        private Cache(String issuer, Map<String, JsonNode> keys, long expiresAtMillis) {
            this.issuer = issuer;
            this.keys = keys;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class AuthFailure extends Exception {
    }
}
