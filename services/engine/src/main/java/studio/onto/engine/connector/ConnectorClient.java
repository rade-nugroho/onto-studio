package studio.onto.engine.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import studio.onto.engine.EnvFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP client for the OpenConnector gateway ({@code OPEN_CONNECTOR_URL}).
 */
public final class ConnectorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final String baseUrl;
    private String bearerToken;
    private boolean tokenResolved;
    private ConnectorException tokenError;

    public ConnectorClient() {
        this(defaultBaseUrl(), null, true);
    }

    ConnectorClient(String baseUrl, String bearerToken) {
        this(baseUrl, bearerToken, false);
    }

    private ConnectorClient(String baseUrl, String bearerToken, boolean resolveSecrets) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? defaultBaseUrl() : baseUrl.replaceAll("/+$", "");
        this.bearerToken = bearerToken;
        this.tokenResolved = !resolveSecrets;
    }

    private static String defaultBaseUrl() {
        String override = System.getProperty("studio.onto.openConnectorUrl");
        if (override != null && !override.isBlank()) {
            return override.replaceAll("/+$", "");
        }
        String configured = EnvFile.get("OPEN_CONNECTOR_URL");
        return configured == null || configured.isBlank() ? "http://127.0.0.1:3000" : configured.replaceAll("/+$", "");
    }

    public boolean isGatewayConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public List<Map<String, Object>> listProviderCatalog(String query, int limit) throws ConnectorException {
        JsonNode root = getJson("/api/providers");
        if (root.has("success")) {
            root = root.path("data");
        }
        List<Map<String, Object>> apps = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (root.isArray()) {
            for (JsonNode provider : root) {
                if (appendProviderRow(apps, provider, null, needle, limit)) {
                    break;
                }
            }
        } else if (root.isObject()) {
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                String serviceId = names.next();
                JsonNode provider = root.get(serviceId);
                if (appendProviderRow(apps, provider, serviceId, needle, limit)) {
                    break;
                }
            }
        }
        apps.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return apps;
    }

    private boolean appendProviderRow(List<Map<String, Object>> apps, JsonNode provider, String serviceId,
                                      String needle, int limit) {
        if (provider == null || !provider.isObject()) {
            return false;
        }
        String id = text(provider, "id", serviceId == null ? "" : serviceId);
        if (id.isBlank() && serviceId != null) {
            id = serviceId;
        }
        if (id.isBlank()) {
            return false;
        }
        String name = text(provider, "name", id);
        if (!needle.isEmpty()
                && !id.toLowerCase(Locale.ROOT).contains(needle)
                && !name.toLowerCase(Locale.ROOT).contains(needle)) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("authType", resolveAuthType(provider));
        row.put("locallyExecutable", provider.path("locallyExecutable").asBoolean(
                provider.path("hasLocalExecutor").asBoolean(false)));
        apps.add(row);
        return apps.size() >= limit;
    }

    public List<Map<String, Object>> listConnections() throws ConnectorException {
        JsonNode envelope = getEnvelope("/v1/connections");
        JsonNode data = envelope.path("data");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                rows.add(parseConnection(item));
            }
        } else if (data.isObject() && data.has("connections") && data.get("connections").isArray()) {
            for (JsonNode item : data.get("connections")) {
                rows.add(parseConnection(item));
            }
        }
        return rows;
    }

    public List<Map<String, Object>> listReadActions(String service) throws ConnectorException {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("Missing app service id");
        }
        JsonNode envelope = getEnvelope("/v1/actions?service=" + encodeQuery(service));
        JsonNode data = envelope.path("data");
        List<Map<String, Object>> actions = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode action : data) {
                maybeAddReadAction(actions, action);
            }
        } else if (data.isObject()) {
            Iterator<String> ids = data.fieldNames();
            while (ids.hasNext()) {
                maybeAddReadAction(actions, data.get(ids.next()));
            }
        }
        actions.sort((a, b) -> String.valueOf(a.get("id")).compareToIgnoreCase(String.valueOf(b.get("id"))));
        return actions;
    }

    public JsonNode executeReadAction(String actionId, String connectionAlias, Map<String, Object> input)
            throws ConnectorException {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("Missing action id");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input == null ? Map.of() : input);
        if (connectionAlias != null && !connectionAlias.isBlank() && !"default".equals(connectionAlias)) {
            body.put("connectionName", connectionAlias);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/actions/" + encodePathSegment(actionId)))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)));
        if (connectionAlias != null && !connectionAlias.isBlank() && !"default".equals(connectionAlias)) {
            builder.header("x-oo-connector-alias", connectionAlias);
        }
        applyAuth(builder);
        JsonNode envelope = readEnvelope(send(builder.build()));
        if (!envelope.path("success").asBoolean(false)) {
            String code = text(envelope, "errorCode", "gateway_error");
            String message = text(envelope, "message", "Action execution failed");
            throw new ConnectorException(message, code);
        }
        return envelope.path("data");
    }

    private void maybeAddReadAction(List<Map<String, Object>> actions, JsonNode action) {
        if (action == null || !action.isObject()) {
            return;
        }
        String operationType = text(action, "operationType", "").toLowerCase(Locale.ROOT);
        if (!operationType.isEmpty() && !"read".equals(operationType)) {
            return;
        }
        String id = text(action, "id", text(action, "actionId", ""));
        if (id.isBlank()) {
            return;
        }
        if (!operationType.isEmpty()) {
            addReadAction(actions, action, id);
            return;
        }
        String lowered = id.toLowerCase(Locale.ROOT);
        if (lowered.contains(".create")
                || lowered.contains(".update")
                || lowered.contains(".delete")
                || lowered.contains(".remove")
                || lowered.contains(".write")
                || lowered.contains(".send")
                || lowered.contains(".post")
                || lowered.contains(".destroy")) {
            return;
        }
        addReadAction(actions, action, id);
    }

    private void addReadAction(List<Map<String, Object>> actions, JsonNode action, String id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("description", text(action, "description", text(action, "summary", id)));
        row.put("outputFields", outputFieldNames(action.path("outputSchema")));
        actions.add(row);
    }

    private List<String> outputFieldNames(JsonNode schema) {
        List<String> fields = new ArrayList<>();
        collectOutputFields(schema, fields);
        return fields.stream().distinct().sorted().toList();
    }

    private void collectOutputFields(JsonNode schema, List<String> fields) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!fields.contains(name)) {
                    fields.add(name);
                }
            }
        }
        JsonNode items = schema.path("items");
        if (!items.isMissingNode()) {
            collectOutputFields(items, fields);
        }
    }

    private Map<String, Object> parseConnection(JsonNode item) {
        Map<String, Object> row = new LinkedHashMap<>();
        String service = text(item, "service", text(item, "provider", ""));
        row.put("service", service);
        row.put("name", text(item, "alias", text(item, "connectionName", "default")));
        row.put("authType", text(item, "authType", text(item, "credentialType", "unknown")));
        String status = text(item, "status", "connected");
        boolean reconnect = "reauth_required".equalsIgnoreCase(status)
                || "disconnected".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status);
        row.put("status", reconnect ? "reconnect" : "connected");
        row.put("appId", text(item, "appId", text(item, "id", "")));
        return row;
    }

    private String resolveAuthType(JsonNode provider) {
        JsonNode auth = provider.path("auth");
        if (auth.isObject()) {
            if (auth.has("type")) {
                return auth.get("type").asText("unknown");
            }
            if (auth.has("types") && auth.get("types").isArray() && !auth.get("types").isEmpty()) {
                return auth.get("types").get(0).asText("unknown");
            }
        }
        JsonNode credentials = provider.path("credentials");
        if (credentials.isArray() && !credentials.isEmpty()) {
            return credentials.get(0).path("type").asText("unknown");
        }
        return "unknown";
    }

    private JsonNode getEnvelope(String path) throws ConnectorException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .GET();
        applyAuth(builder);
        return readEnvelope(send(builder.build()));
    }

    private JsonNode getJson(String path) throws ConnectorException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .GET();
        applyAuth(builder);
        return readJson(send(builder.build()));
    }

    private HttpResponse<String> send(HttpRequest request) throws ConnectorException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                throw new ConnectorException("Open Connector gateway error (HTTP " + response.statusCode() + ")",
                        "gateway_unavailable");
            }
            if (response.statusCode() == 404) {
                throw new ConnectorException("Open Connector resource not found", "not_found");
            }
            if (response.statusCode() >= 400) {
                throw new ConnectorException("Open Connector request failed (HTTP " + response.statusCode() + ")",
                        "gateway_error");
            }
            return response;
        } catch (ConnectorException error) {
            throw error;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ConnectorException("Open Connector gateway is unavailable", "gateway_unavailable");
        }
    }

    private JsonNode readEnvelope(HttpResponse<String> response) throws ConnectorException {
        JsonNode root = parseBody(response);
        if (root.has("success")) {
            if (!root.path("success").asBoolean(false)) {
                throw new ConnectorException(
                        text(root, "message", "Open Connector request failed"),
                        text(root, "errorCode", "gateway_error"));
            }
            return root;
        }
        return root;
    }

    private JsonNode readJson(HttpResponse<String> response) throws ConnectorException {
        return parseBody(response);
    }

    private JsonNode parseBody(HttpResponse<String> response) throws ConnectorException {
        try {
            return MAPPER.readTree(response.body() == null ? "{}" : response.body());
        } catch (IOException error) {
            throw new ConnectorException("Invalid JSON from Open Connector gateway", "gateway_error");
        }
    }

    private void applyAuth(HttpRequest.Builder builder) throws ConnectorException {
        String token = resolvedToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    private String resolvedToken() throws ConnectorException {
        if (tokenError != null) {
            throw tokenError;
        }
        if (tokenResolved) {
            return bearerToken;
        }
        synchronized (this) {
            if (tokenError != null) {
                throw tokenError;
            }
            if (tokenResolved) {
                return bearerToken;
            }
            try {
                bearerToken = InfisicalSecrets.connectorToken();
                tokenResolved = true;
                return bearerToken;
            } catch (InfisicalException error) {
                tokenError = new ConnectorException(
                        "Could not read connector credentials from Infisical",
                        "gateway_unavailable");
                throw tokenError;
            }
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (value.isTextual()) {
            return value.asText(fallback);
        }
        return value.asText(fallback);
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePathSegment(String value) {
        return URI.create("http://x/" + value.replace(" ", "%20")).getRawPath().substring(1);
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}