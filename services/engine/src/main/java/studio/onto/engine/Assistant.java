package studio.onto.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ontology-grounded assistant. Completions are OpenRouter chat requests
 * ({@code POST /chat/completions}, model {@code deepseek/deepseek-v4.1-flash-1017}).
 * Ontology tools run through this engine's HTTP API, so edits stay on the undo
 * stack and in {@code .onto-studio/evidence.jsonl}.
 */
public final class Assistant implements AutoCloseable {

    static final String MODEL = "deepseek/deepseek-v4.1-flash-1017";
    static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    static final String BASE_URL_PROPERTY = "studio.onto.openRouterUrl";
    static final String API_KEY_PROPERTY = "studio.onto.openRouterApiKey";

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_TOOL_OUTPUT = 50_000;
    private static final int MAX_ROUNDS = 8;

    private static final String SYSTEM_PROMPT = """
            You are the assistant inside Onto Studio, an ontology workbench.
            Use the ontology tools to inspect the open ontology before you answer.
            Read tools: ontology_search, ontology_entity, ontology_list.
            Write tools: ontology_add_subclass, ontology_remove_subclass,
            ontology_add_axiom, ontology_remove_axiom, ontology_apply_batch.
            File tools: ontology_open, ontology_new, ontology_save, document_read.

            When the user gives a PRD, RFQ, or other requirements document:
            1. Call document_read for every source path. Never guess content you did not read.
            2. If the user wants a new model, call ontology_new with an .owl path first.
            3. Derive classes, object properties, data properties, and relationships only from the document.
            4. Write Manchester frames and apply them in one ontology_apply_batch call.
               Give every root class SubClassOf: Thing so the class is created.
               Every class and property must carry Annotations: rdfs:label and
               Annotations: rdfs:comment; the comment explains the concept in one sentence.
            5. Call ontology_save so the generated file is written to disk.
            6. Summarize the model, list assumptions, and flag gaps or ambiguous requirements.

            Prefer reading before writing. Never invent an IRI: resolve it with
            ontology_search first. Report exactly which tools you used.
            Axioms use DL syntax: subsumption is A sub B, existential is
            A sub exists r.B. Keep answers short and factual.
            """;

    private final Workspace workspace;
    private final Object lock = new Object();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile String baseUrl = "";
    private volatile String token = "";

    public Assistant(Workspace workspace) {
        this.workspace = workspace;
    }

    public void setEndpoint(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    @Override
    public void close() {
        // Chat completions are stateless; there is no remote session to close.
    }

    public void answer(String question, String entityIri, Context ctx) {
        String apiKey = apiKey();
        if (apiKey == null) {
            ctx.status(503).json(Map.of(
                    "error", "Set OPENROUTER_API_KEY in the environment to use the assistant"));
            return;
        }
        if (headerUnsafe(apiKey)) {
            ctx.status(503).json(Map.of("error", "OPENROUTER_API_KEY is not a valid header value"));
            return;
        }
        if (question == null || question.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing question"));
            return;
        }
        try {
            String text;
            synchronized (lock) {
                text = ask(apiKey, question, entityIri);
            }
            if (text.isBlank()) {
                text = "The assistant returned no text.";
            }
            ctx.json(Map.of("answer", text));
        } catch (AssistantFailure error) {
            ctx.status(502).json(Map.of("error", redact(error.getMessage(), apiKey)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            ctx.status(502).json(Map.of("error", "Assistant request failed"));
        } catch (HttpTimeoutException error) {
            ctx.status(502).json(Map.of("error", "OpenRouter request timed out"));
        } catch (Exception error) {
            ctx.status(502).json(Map.of("error", failureMessage(error, apiKey)));
        }
    }

    private String ask(String apiKey, String question, String entityIri) throws Exception {
        String context = workspace.ontologyContext(entityIri);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of(
                "role", "user",
                "content", "Ontology context:\n" + context + "\n\nQuestion: " + question));
        for (int round = 0; round < MAX_ROUNDS; round++) {
            JsonNode message = complete(apiKey, messages);
            JsonNode calls = message.get("tool_calls");
            if (calls == null || !calls.isArray() || calls.isEmpty()) {
                return messageText(message).trim();
            }
            messages.add(assistantTurn(message, calls));
            for (JsonNode call : calls) {
                messages.add(toolResult(call));
            }
        }
        throw new AssistantFailure("Assistant exceeded " + MAX_ROUNDS + " tool rounds");
    }

    private JsonNode complete(String apiKey, List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", MODEL);
        payload.put("messages", messages);
        payload.put("tools", toolSpecs());
        payload.put("stream", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(openRouterBase() + COMPLETIONS_PATH))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String raw = response.body() == null ? "" : response.body();
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new AssistantFailure(upstreamError(status, raw, apiKey));
        }
        if (raw.isBlank()) {
            throw new AssistantFailure("OpenRouter returned an empty chat completion");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception error) {
            throw new AssistantFailure("OpenRouter returned a non-JSON chat completion");
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new AssistantFailure("OpenRouter returned no chat completion choices");
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.isObject()) {
            throw new AssistantFailure("OpenRouter returned no assistant message");
        }
        return message;
    }

    private Map<String, Object> assistantTurn(JsonNode message, JsonNode calls) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("role", "assistant");
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            turn.put("content", null);
        } else {
            turn.put("content", mapper.convertValue(content, Object.class));
        }
        turn.put("tool_calls", mapper.convertValue(calls, new TypeReference<List<Object>>() {
        }));
        return turn;
    }

    private Map<String, Object> toolResult(JsonNode call) {
        String id = call.path("id").asText("");
        if (id.isBlank()) {
            throw new AssistantFailure("OpenRouter returned a tool call without an id");
        }
        String name = call.path("function").path("name").asText("");
        String result;
        try {
            result = runTool(name, arguments(call.path("function")));
        } catch (Exception error) {
            result = "Tool failed: " + (error.getMessage() == null ? "invalid arguments" : error.getMessage());
        }
        return Map.of("role", "tool", "tool_call_id", id, "content", result);
    }

    private Map<String, Object> arguments(JsonNode function) throws Exception {
        JsonNode args = function.get("arguments");
        if (args == null || args.isNull()) {
            return Map.of();
        }
        if (args.isObject()) {
            return mapper.convertValue(args, new TypeReference<Map<String, Object>>() {
            });
        }
        String raw = args.asText();
        if (raw.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
        });
    }

    private static String messageText(JsonNode message) {
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    text.append(part.asText());
                } else if (part.hasNonNull("text")) {
                    text.append(part.get("text").asText());
                }
            }
            return text.toString();
        }
        return "";
    }

    private String runTool(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "ontology_search" -> get("/search?q=" + encode(text(args, "query"))
                        + (text(args, "kind") == null ? "" : "&kind=" + encode(text(args, "kind"))));
                case "ontology_entity" -> get("/entity?iri=" + encode(require(args, "iri")));
                case "ontology_list" -> get("/search?q=&kind=" + encode(require(args, "kind")));
                case "ontology_add_subclass" -> post("/axiom/subclass", Map.of(
                        "sub", require(args, "sub"), "sup", require(args, "sup")));
                case "ontology_remove_subclass" -> post("/axiom/subclass/remove", Map.of(
                        "sub", require(args, "sub"), "sup", require(args, "sup")));
                case "ontology_add_axiom" -> post("/axiom/add-text", Map.of(
                        "text", require(args, "text")));
                case "ontology_remove_axiom" -> post("/axiom/remove-rendered", Map.of(
                        "iri", require(args, "iri"), "text", require(args, "text")));
                case "ontology_save" -> text(args, "path") == null
                        ? post("/ontology/save", Map.of())
                        : post("/ontology/save", Map.of("path", text(args, "path")));
                case "ontology_open" -> post("/ontology/open", Map.of(
                        "path", require(args, "path")));
                case "ontology_new" -> post("/ontology/new", Map.of(
                        "path", require(args, "path")));
                case "ontology_apply_batch" -> post("/axiom/batch", Map.of(
                        "text", require(args, "text")));
                case "document_read" -> post("/document/extract", Map.of(
                        "path", require(args, "path")));
                default -> "Unknown tool: " + name;
            };
        } catch (Exception error) {
            return "Tool failed: " + error.getMessage();
        }
    }

    private String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Engine-Token", token)
                .timeout(TOOL_TIMEOUT)
                .GET()
                .build();
        return send(request);
    }

    private String post(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Engine-Token", token)
                .header("Content-Type", "application/json")
                .timeout(TOOL_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return send(request);
    }

    private String send(HttpRequest request) throws Exception {
        if (baseUrl.isBlank()) {
            return "Tool failed: engine endpoint is not ready";
        }
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (body.length() > MAX_TOOL_OUTPUT) {
            body = body.substring(0, MAX_TOOL_OUTPUT) + "\n[truncated]";
        }
        return response.statusCode() >= 400
                ? "HTTP " + response.statusCode() + ": " + body
                : body;
    }

    private List<Map<String, Object>> toolSpecs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (Tool tool : tools()) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", tool.properties());
            if (!tool.required().isEmpty()) {
                parameters.put("required", tool.required());
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", parameters);
            specs.add(Map.of("type", "function", "function", function));
        }
        return specs;
    }

    private static List<Tool> tools() {
        return List.of(
                tool("document_read",
                        "Read a PRD, RFQ, or other requirements document from the local filesystem and return "
                                + "its plain text. Supports PDF, DOCX, and UTF-8 text formats. Use this before "
                                + "deriving any ontology term from a document; never invent content. "
                                + "The result may be truncated at 60,000 characters.",
                        Map.of("path", Map.of("type", "string", "description", "Absolute path to the document.")),
                        List.of("path")),
                tool("ontology_new",
                        "Create a new empty OWL ontology at the given path and open it in the workspace. "
                                + "Use this when the user wants a fresh model built from a PRD or RFQ. "
                                + "Unsaved edits in the current workspace are discarded.",
                        Map.of("path", Map.of("type", "string", "description", "Absolute path for the new .owl file.")),
                        List.of("path")),
                tool("ontology_apply_batch",
                        "Apply many Manchester frame axioms in one undoable edit. Use this to create the "
                                + "ontology in a single step after reading a document. Every root class must "
                                + "include SubClassOf: Thing so the class is created. Returns the rendered "
                                + "axioms that were added.",
                        Map.of("text", Map.of("type", "string", "description", "One or more Manchester frames separated by blank lines.")),
                        List.of("text")),
                tool("ontology_search",
                        "Search the open ontology by label or IRI fragment. Use this first to resolve a "
                                + "class, property, or individual to an exact IRI before any read or write. "
                                + "Returns at most 50 matches with their IRI, label, and kind. "
                                + "Set kind to filter: class, object property, data property, annotation property, individual.",
                        Map.of(
                                "query", Map.of("type", "string", "description", "Text to match against labels and IRIs. Empty returns nothing unless kind is set."),
                                "kind", Map.of("type", "string", "description", "Optional kind filter.")),
                        List.of()),
                tool("ontology_entity",
                        "Read one entity from the open ontology. Returns its label, kind, superclasses, "
                                + "domain, range, types, annotations, and axioms in DL syntax. "
                                + "Use it after ontology_search resolved an IRI.",
                        Map.of("iri", Map.of("type", "string", "description", "Exact entity IRI.")),
                        List.of("iri")),
                tool("ontology_list",
                        "List every entity of one kind in the open ontology. Use it to enumerate all "
                                + "classes, properties, or individuals when the user asks for a complete list. "
                                + "Returns IRI, label, and kind for up to 50 entities.",
                        Map.of("kind", Map.of("type", "string", "description", "One of: class, object property, data property, annotation property, individual.")),
                        List.of("kind")),
                tool("ontology_add_subclass",
                        "Add a SubClassOf axiom between two existing classes. Both arguments must be exact "
                                + "IRIs resolved with ontology_search. The edit is undoable in the UI. "
                                + "Do not use it for individuals or properties.",
                        Map.of(
                                "sub", Map.of("type", "string", "description", "IRI of the subclass."),
                                "sup", Map.of("type", "string", "description", "IRI of the superclass.")),
                        List.of("sub", "sup")),
                tool("ontology_remove_subclass",
                        "Remove an existing SubClassOf axiom between two classes. Use ontology_entity "
                                + "first to confirm the axiom exists. The edit is undoable in the UI.",
                        Map.of(
                                "sub", Map.of("type", "string", "description", "IRI of the subclass."),
                                "sup", Map.of("type", "string", "description", "IRI of the superclass.")),
                        List.of("sub", "sup")),
                tool("ontology_add_axiom",
                        "Add axioms written in Manchester frame syntax, for example 'Class: Pizza "
                                + "SubClassOf: Food'. Use it only when the structured subclass tool cannot "
                                + "express the axiom. The edit is undoable in the UI.",
                        Map.of("text", Map.of("type", "string", "description", "Manchester frame syntax.")),
                        List.of("text")),
                tool("ontology_remove_axiom",
                        "Remove one axiom from an entity by its rendered DL text. Read the entity first "
                                + "with ontology_entity and pass the exact axiom string shown there.",
                        Map.of(
                                "iri", Map.of("type", "string", "description", "IRI of the entity that owns the axiom."),
                                "text", Map.of("type", "string", "description", "Exact rendered axiom text.")),
                        List.of("iri", "text")),
                tool("ontology_save",
                        "Save the open ontology to disk. Omit path to overwrite the file that was opened; "
                                + "pass a path to save a copy. Only call it when the user asks to save.",
                        Map.of("path", Map.of("type", "string", "description", "Optional absolute file path.")),
                        List.of()),
                tool("ontology_open",
                        "Open an ontology file from the local filesystem and replace the current workspace. "
                                + "Only call it when the user explicitly asks to open a specific file, because "
                                + "unsaved edits are discarded.",
                        Map.of("path", Map.of("type", "string", "description", "Absolute path to an OWL, TTL, RDF, XML, or OFN file.")),
                        List.of("path")));
    }

    private static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        return new Tool(name, description, properties, required);
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String require(Map<String, Object> args, String key) {
        String value = text(args, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String apiKey() {
        String value = RuntimeConfig.get(API_KEY_PROPERTY, "OPENROUTER_API_KEY");
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String openRouterBase() {
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
            JsonNode error = new ObjectMapper().readTree(raw).path("error");
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

    private static String failureMessage(Exception error, String apiKey) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "Assistant request failed";
        }
        return redact("Assistant failed: " + message, apiKey);
    }

    private static String redact(String text, String apiKey) {
        if (text == null || text.isBlank()) {
            return "Assistant request failed";
        }
        if (apiKey != null && apiKey.length() >= 8 && text.contains(apiKey)) {
            return text.replace(apiKey, "[redacted]");
        }
        return text;
    }

    private static final class AssistantFailure extends RuntimeException {
        private AssistantFailure(String message) {
            super(message);
        }
    }

    private record Tool(String name, String description, Map<String, Object> properties, List<String> required) {
    }
}
