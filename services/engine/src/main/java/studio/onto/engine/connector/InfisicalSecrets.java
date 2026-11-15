package studio.onto.engine.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import studio.onto.engine.RuntimeConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads connector credentials from Infisical when universal auth is configured.
 * Login is {@code POST /api/v1/auth/universal-auth/login}. The secret value is
 * {@code GET /api/v4/secrets/{secretName}} (the v3 raw read is deprecated).
 * Secret values are not logged.
 */
public final class InfisicalSecrets {

    static final String CONNECTOR_TOKEN = "OPEN_CONNECTOR_TOKEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BODY_CHARS = 1_000_000;
    private static final String DEFAULT_SITE = "https://app.infisical.com";
    private static final String DEFAULT_ENVIRONMENT = "dev";

    private InfisicalSecrets() {
    }

    /**
     * Connector bearer token. When the Infisical client id, client secret, and
     * project id are all set, the value comes from Infisical. Otherwise it is
     * {@code OPEN_CONNECTOR_TOKEN} from the environment.
     */
    public static String connectorToken() throws InfisicalException {
        Settings settings = settings();
        if (!settings.configured()) {
            return RuntimeConfig.get("studio.onto.openConnectorToken", CONNECTOR_TOKEN);
        }
        return readSecret(settings, CONNECTOR_TOKEN);
    }

    private static String readSecret(Settings settings, String secretName) throws InfisicalException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String accessToken = login(http, settings);
        String secretUrl = settings.origin()
                + "/api/v4/secrets/" + url(secretName)
                + "?projectId=" + url(settings.projectId)
                + "&environment=" + url(settings.environment)
                + "&secretPath=" + url("/");
        HttpRequest request = HttpRequest.newBuilder(sameOrigin(settings.site, secretUrl))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        JsonNode body = send(http, request);
        String value = body.path("secret").path("secretValue").asText("");
        if (value.isEmpty()) {
            throw new InfisicalException("Infisical secret read failed");
        }
        return value;
    }

    private static String login(HttpClient http, Settings settings) throws InfisicalException {
        String json;
        try {
            json = MAPPER.writeValueAsString(java.util.Map.of(
                    "clientId", settings.clientId,
                    "clientSecret", settings.clientSecret));
        } catch (IOException error) {
            throw new InfisicalException("Infisical login failed");
        }
        HttpRequest request = HttpRequest.newBuilder(sameOrigin(settings.site, settings.origin()
                        + "/api/v1/auth/universal-auth/login"))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        JsonNode body = send(http, request);
        String accessToken = body.path("accessToken").asText("");
        if (accessToken.isEmpty()) {
            throw new InfisicalException("Infisical login failed");
        }
        return accessToken;
    }

    private static JsonNode send(HttpClient http, HttpRequest request) throws InfisicalException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new InfisicalException("Infisical request failed");
            }
            String body = response.body();
            if (body == null || body.isBlank() || body.length() > MAX_BODY_CHARS) {
                throw new InfisicalException("Infisical request failed");
            }
            return MAPPER.readTree(body);
        } catch (InfisicalException error) {
            throw error;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new InfisicalException("Infisical is unavailable");
        }
    }

    private static Settings settings() throws InfisicalException {
        String clientId = RuntimeConfig.get("studio.onto.infisical.clientId", "INFISICAL_CLIENT_ID");
        String clientSecret = RuntimeConfig.get("studio.onto.infisical.clientSecret", "INFISICAL_CLIENT_SECRET");
        String projectId = RuntimeConfig.get("studio.onto.infisical.projectId", "INFISICAL_PROJECT_ID");
        if (clientId == null || clientSecret == null || projectId == null) {
            return Settings.disabled();
        }
        String site = RuntimeConfig.get("studio.onto.infisical.siteUrl", "INFISICAL_SITE_URL");
        if (site == null) {
            site = DEFAULT_SITE;
        }
        String environment = RuntimeConfig.get("studio.onto.infisical.environment", "INFISICAL_ENVIRONMENT");
        if (environment == null) {
            environment = DEFAULT_ENVIRONMENT;
        }
        URI parsed = parseSite(site);
        return new Settings(parsed, clientId, clientSecret, projectId, environment);
    }

    private static URI parseSite(String site) throws InfisicalException {
        URI uri;
        try {
            uri = URI.create(site.trim());
        } catch (IllegalArgumentException error) {
            throw new InfisicalException("Infisical site URL is invalid");
        }
        String scheme = uri.getScheme();
        String path = uri.getRawPath();
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new InfisicalException("Infisical site URL is invalid");
        }
        return uri;
    }

    private static URI sameOrigin(URI site, String requestUrl) throws InfisicalException {
        URI request;
        try {
            request = URI.create(requestUrl);
        } catch (IllegalArgumentException error) {
            throw new InfisicalException("Infisical request URL is invalid");
        }
        if (site.getHost() == null
                || request.getHost() == null
                || !site.getScheme().equalsIgnoreCase(request.getScheme())
                || !site.getHost().equalsIgnoreCase(request.getHost())
                || port(site) != port(request)) {
            throw new InfisicalException("Refusing to call a URL outside the Infisical site");
        }
        return request;
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class Settings {
        private final URI site;
        private final String clientId;
        private final String clientSecret;
        private final String projectId;
        private final String environment;

        private Settings(URI site, String clientId, String clientSecret, String projectId, String environment) {
            this.site = site;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.projectId = projectId;
            this.environment = environment;
        }

        private static Settings disabled() {
            return new Settings(null, null, null, null, null);
        }

        private boolean configured() {
            return site != null;
        }

        private String origin() {
            String portSuffix = site.getPort() == -1 ? "" : ":" + site.getPort();
            return site.getScheme() + "://" + site.getHost() + portSuffix;
        }

        @Override
        public String toString() {
            return "InfisicalSettings{configured=" + configured() + "}";
        }
    }
}

final class InfisicalException extends Exception {

    InfisicalException(String message) {
        super(message);
    }
}
