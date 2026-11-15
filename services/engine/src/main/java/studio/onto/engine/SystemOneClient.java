package studio.onto.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Forwards one System One request to
 * {@code POST https://openrouter.ai/api/v1/systemone}.
 * The bearer token is {@code OPENROUTER_API_KEY}. It is never logged.
 */
public final class SystemOneClient {

    static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    static final String BASE_URL_PROPERTY = "studio.onto.openRouterUrl";
    static final String API_KEY_PROPERTY = "studio.onto.openRouterApiKey";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    public SystemOneClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void submit(Context ctx) {
        String apiKey = apiKey();
        if (apiKey == null) {
            ctx.status(503).json(Map.of(
                    "error", "Set OPENROUTER_API_KEY in the environment to use System One"));
            return;
        }
        if (headerUnsafe(apiKey)) {
            ctx.status(503).json(Map.of("error", "OPENROUTER_API_KEY is not a valid header value"));
            return;
        }
        String raw = ctx.body();
        if (raw == null || raw.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing request body"));
            return;
        }
        final JsonNode body;
        try {
            body = MAPPER.readTree(raw);
        } catch (Exception error) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        String problem = invalid(body);
        if (problem != null) {
            ctx.status(400).json(Map.of("error", problem));
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/systemone"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(raw))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            writeUpstream(ctx, response.statusCode(), response.body() == null ? "" : response.body(), apiKey);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            ctx.status(502).json(Map.of("error", "System One request failed"));
        } catch (Exception error) {
            ctx.status(502).json(Map.of("error", redact(failureMessage(error), apiKey)));
        }
    }

    private static void writeUpstream(Context ctx, int status, String raw, String apiKey) {
        if (status >= 200 && status < 300) {
            if (raw.isBlank()) {
                ctx.status(502).json(Map.of("error", "OpenRouter returned an empty System One response"));
                return;
            }
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(raw);
            } catch (Exception error) {
                ctx.status(502).json(Map.of("error", "OpenRouter returned a non-JSON System One response"));
                return;
            }
            if (!parsed.isObject()) {
                ctx.status(502).json(Map.of(
                        "error", "OpenRouter returned a System One response that is not a JSON object"));
                return;
            }
            ctx.status(status);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(raw);
            return;
        }
        ctx.status(502).json(Map.of("error", upstreamError(status, raw, apiKey)));
    }

    private static String invalid(JsonNode body) {
        if (body == null || !body.isObject()) {
            return "System One request must be a JSON object";
        }
        JsonNode model = body.get("model");
        if (model == null || model.isNull() || (model.isTextual() && model.asText().isBlank())) {
            return "Missing model";
        }
        if (!model.isTextual()) {
            return "model must be a string";
        }
        if (!body.has("state") || body.get("state") == null || body.get("state").isNull()) {
            return "Missing state";
        }
        JsonNode state = body.get("state");
        if (!state.isTextual() && !state.isObject() && !state.isArray()) {
            return "state must be a string, object, or array";
        }
        JsonNode questions = body.get("questions");
        if (questions == null || questions.isNull()) {
            return "Missing questions";
        }
        if (!questions.isObject()) {
            return "questions must be an object";
        }
        return null;
    }

    private static String upstreamError(int status, String raw, String apiKey) {
        String detail = errorMessage(raw);
        String text = detail == null
                ? "OpenRouter returned " + status
                : "OpenRouter returned " + status + ": " + detail;
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        return redact(text, apiKey);
    }

    private static String errorMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(raw).path("error");
            if (error.isObject()) {
                JsonNode message = error.get("message");
                if (message != null && message.isTextual() && !message.asText().isBlank()) {
                    return message.asText();
                }
            } else if (error.isTextual() && !error.asText().isBlank()) {
                return error.asText();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String apiKey() {
        String value = RuntimeConfig.get(API_KEY_PROPERTY, "OPENROUTER_API_KEY");
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String baseUrl() {
        String override = System.getProperty(BASE_URL_PROPERTY);
        if (override == null || override.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = override.trim().replaceAll("/+$", "");
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme();
        if (uri.getRawUserInfo() != null
                || uri.getHost() == null
                || scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Invalid OpenRouter base URL");
        }
        return trimmed;
    }

    private static boolean headerUnsafe(String apiKey) {
        for (int i = 0; i < apiKey.length(); i++) {
            char ch = apiKey.charAt(i);
            if (ch <= ' ' || ch >= 127) {
                return true;
            }
        }
        return false;
    }

    private static String failureMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "System One request failed";
        }
        return "System One request failed: " + message;
    }

    private static String redact(String text, String apiKey) {
        if (text == null || text.isBlank()) {
            return "System One request failed";
        }
        if (apiKey != null && apiKey.length() >= 8 && text.contains(apiKey)) {
            return text.replace(apiKey, "[redacted]");
        }
        return text;
    }
}
