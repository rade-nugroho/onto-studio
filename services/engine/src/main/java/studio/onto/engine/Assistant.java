package studio.onto.engine;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.agents.AgentCreateParams;
import com.anthropic.models.beta.agents.BetaManagedAgentsCustomToolInputSchema;
import com.anthropic.models.beta.agents.BetaManagedAgentsCustomToolParams;
import com.anthropic.models.beta.agents.BetaManagedAgentsModel;
import com.anthropic.models.beta.environments.BetaCloudConfigParams;
import com.anthropic.models.beta.environments.BetaLimitedNetworkParams;
import com.anthropic.models.beta.environments.EnvironmentCreateParams;
import com.anthropic.models.beta.sessions.SessionCreateParams;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentCustomToolUseEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserCustomToolResultEventParams;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.anthropic.models.beta.sessions.events.EventStreamParams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ontology-grounded assistant built on Claude Managed Agents. The agent runs
 * in a cloud sandbox, but every ontology operation is a custom tool executed by
 * this engine against the open workspace.
 */
public final class Assistant {

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
            5. Call ontology_save so the generated file is written to disk.
            6. Summarize the model, list assumptions, and flag gaps or ambiguous requirements.

            Prefer reading before writing. Never invent an IRI: resolve it with
            ontology_search first. Report exactly which tools you used.
            Axioms use DL syntax: subsumption is A sub B, existential is
            A sub exists r.B. Keep answers short and factual.
            """;

    private static final int MAX_TOOL_OUTPUT = 50_000;
    private static final String DEFAULT_MODEL = "claude-sonnet-5";
    private static final AnthropicBeta BETA = AnthropicBeta.MANAGED_AGENTS_2026_04_01;
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SESSION_BUDGET = Duration.ofMinutes(2);

    private final Workspace workspace;
    private final Object lock = new Object();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path stateFile = Path.of(System.getProperty("user.home"), ".onto-studio", "assistant.json");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private volatile String baseUrl = "";
    private volatile String token = "";
    private String agentId;
    private String environmentId;
    private String sessionId;
    private String sessionPath;
    private String configSignature;

    public Assistant(Workspace workspace) {
        this.workspace = workspace;
        loadState();
    }

    public void setEndpoint(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public void answer(String question, String entityIri, Context ctx) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            ctx.status(503).json(Map.of(
                    "error", "Set ANTHROPIC_API_KEY in the environment to use the assistant"));
            return;
        }
        if (question == null || question.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing question"));
            return;
        }
        try {
            String text;
            synchronized (lock) {
                text = ask(question, entityIri);
            }
            if (text.isBlank()) {
                text = "The assistant returned no text.";
            }
            ctx.json(Map.of("answer", text));
        } catch (Exception error) {
            ctx.status(502).json(Map.of("error", "Assistant failed: " + error.getMessage()));
        }
    }

    private String ask(String question, String entityIri) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        ensureResources(client);
        ensureSession(client);

        String context = workspace.ontologyContext(entityIri);
        String prompt = "Ontology context:\n" + context + "\n\nQuestion: " + question;
        StringBuilder answer = new StringBuilder();

        try (var stream = client.beta().sessions().events().streamStreaming(
                sessionId, EventStreamParams.builder().addBeta(BETA).build())) {
            client.beta().sessions().events().send(sessionId, EventSendParams.builder()
                    .addBeta(BETA)
                    .addEvent(BetaManagedAgentsUserMessageEventParams.builder()
                            .addTextContent(prompt)
                            .build())
                    .build());

            long deadline = System.nanoTime() + SESSION_BUDGET.toNanos();
            for (BetaManagedAgentsStreamSessionEvents event : stream) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Assistant timed out");
                }
                switch (event.type().value()) {
                    case AGENT_MESSAGE -> event.asAgentMessage().content()
                            .forEach(block -> block.text().ifPresent(text -> answer.append(text.text())));
                    case AGENT_CUSTOM_TOOL_USE -> {
                        var use = event.asAgentCustomToolUse();
                        String result = runTool(use.name(), use.input());
                        client.beta().sessions().events().send(sessionId, EventSendParams.builder()
                                .addBeta(BETA)
                                .addEvent(BetaManagedAgentsUserCustomToolResultEventParams.builder()
                                        .customToolUseId(use.id())
                                        .addTextContent(result)
                                        .build())
                                .build());
                    }
                    case SESSION_STATUS_IDLE -> {
                        if (event.asSessionStatusIdle().stopReason().isEndTurn()) {
                            return answer.toString().trim();
                        }
                    }
                    case SESSION_ERROR -> throw new IllegalStateException(
                            event.asSessionError().error().message());
                    default -> {
                    }
                }
            }
        }
        return answer.toString().trim();
    }

    private void ensureResources(AnthropicClient client) {
        String signature = configSignature();
        if (agentId != null && !signature.equals(configSignature)) {
            agentId = null;
            sessionId = null;
            sessionPath = null;
        }
        if (agentId != null && environmentId != null) {
            return;
        }
        if (environmentId == null) {
            var environment = client.beta().environments().create(EnvironmentCreateParams.builder()
                    .addBeta(BETA)
                    .name("onto-studio")
                    .config(BetaCloudConfigParams.builder()
                            .networking(BetaLimitedNetworkParams.builder()
                                    .allowPackageManagers(true)
                                    .build())
                            .build())
                    .build());
            environmentId = environment.id();
        }
        if (agentId == null) {
            AgentCreateParams.Builder builder = AgentCreateParams.builder()
                    .addBeta(BETA)
                    .name("Onto Studio Assistant")
                    .description("Ontology assistant for Onto Studio. Inspects and edits the open OWL ontology.")
                    .model(BetaManagedAgentsModel.of(modelId()))
                    .system(SYSTEM_PROMPT);
            tools().forEach(builder::addTool);
            agentId = client.beta().agents().create(builder.build()).id();
        }
        configSignature = signature;
        saveState();
    }

    private static String modelId() {
        String configured = System.getenv("ANTHROPIC_MODEL");
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }

    private static String configSignature() {
        String tools = tools().stream()
                .map(BetaManagedAgentsCustomToolParams::name)
                .reduce("", (left, right) -> left + "," + right);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((modelId() + "\n" + SYSTEM_PROMPT + "\n" + tools)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ignored) {
            return modelId();
        }
    }

    private void ensureSession(AnthropicClient client) {
        if (sessionId != null && Objects.equals(sessionPath, workspace.currentPath())) {
            return;
        }
        var session = client.beta().sessions().create(SessionCreateParams.builder()
                .addBeta(BETA)
                .agent(agentId)
                .environmentId(environmentId)
                .build());
        sessionId = session.id();
        sessionPath = workspace.currentPath();
        saveState();
    }

    private String runTool(String name, BetaManagedAgentsAgentCustomToolUseEvent.Input input) {
        Map<String, Object> args = new LinkedHashMap<>();
        input._additionalProperties().forEach((key, value) -> args.put(key, value.convert(Object.class)));
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

    private static List<BetaManagedAgentsCustomToolParams> tools() {
        return List.of(
                tool("document_read",
                        "Read a PRD, RFQ, or other requirements document from the local filesystem and return "
                                + "its plain text. Supports PDF, DOCX, and UTF-8 text formats. Use this before "
                                + "deriving any ontology term from a document; never invent content. "
                                + "The result may be truncated at 60,000 characters.",
                        schema(Map.of(
                                "path", Map.of("type", "string", "description", "Absolute path to the document.")), List.of("path"))),
                tool("ontology_new",
                        "Create a new empty OWL ontology at the given path and open it in the workspace. "
                                + "Use this when the user wants a fresh model built from a PRD or RFQ. "
                                + "Unsaved edits in the current workspace are discarded.",
                        schema(Map.of(
                                "path", Map.of("type", "string", "description", "Absolute path for the new .owl file.")), List.of("path"))),
                tool("ontology_apply_batch",
                        "Apply many Manchester frame axioms in one undoable edit. Use this to create the "
                                + "ontology in a single step after reading a document. Every root class must "
                                + "include SubClassOf: Thing so the class is created. Returns the rendered "
                                + "axioms that were added.",
                        schema(Map.of(
                                "text", Map.of("type", "string", "description", "One or more Manchester frames separated by blank lines.")), List.of("text"))),
                tool("ontology_search",
                        "Search the open ontology by label or IRI fragment. Use this first to resolve a "
                                + "class, property, or individual to an exact IRI before any read or write. "
                                + "Returns at most 50 matches with their IRI, label, and kind. "
                                + "Set kind to filter: class, object property, data property, annotation property, individual.",
                        schema(Map.of(
                                "query", Map.of("type", "string", "description", "Text to match against labels and IRIs. Empty returns nothing unless kind is set."),
                                "kind", Map.of("type", "string", "description", "Optional kind filter.")), List.of())),
                tool("ontology_entity",
                        "Read one entity from the open ontology. Returns its label, kind, superclasses, "
                                + "domain, range, types, annotations, and axioms in DL syntax. "
                                + "Use it after ontology_search resolved an IRI.",
                        schema(Map.of(
                                "iri", Map.of("type", "string", "description", "Exact entity IRI.")), List.of("iri"))),
                tool("ontology_list",
                        "List every entity of one kind in the open ontology. Use it to enumerate all "
                                + "classes, properties, or individuals when the user asks for a complete list. "
                                + "Returns IRI, label, and kind for up to 50 entities.",
                        schema(Map.of(
                                "kind", Map.of("type", "string", "description", "One of: class, object property, data property, annotation property, individual.")), List.of("kind"))),
                tool("ontology_add_subclass",
                        "Add a SubClassOf axiom between two existing classes. Both arguments must be exact "
                                + "IRIs resolved with ontology_search. The edit is undoable in the UI. "
                                + "Do not use it for individuals or properties.",
                        schema(Map.of(
                                "sub", Map.of("type", "string", "description", "IRI of the subclass."),
                                "sup", Map.of("type", "string", "description", "IRI of the superclass.")), List.of("sub", "sup"))),
                tool("ontology_remove_subclass",
                        "Remove an existing SubClassOf axiom between two classes. Use ontology_entity "
                                + "first to confirm the axiom exists. The edit is undoable in the UI.",
                        schema(Map.of(
                                "sub", Map.of("type", "string", "description", "IRI of the subclass."),
                                "sup", Map.of("type", "string", "description", "IRI of the superclass.")), List.of("sub", "sup"))),
                tool("ontology_add_axiom",
                        "Add axioms written in Manchester frame syntax, for example 'Class: Pizza "
                                + "SubClassOf: Food'. Use it only when the structured subclass tool cannot "
                                + "express the axiom. The edit is undoable in the UI.",
                        schema(Map.of(
                                "text", Map.of("type", "string", "description", "Manchester frame syntax.")), List.of("text"))),
                tool("ontology_remove_axiom",
                        "Remove one axiom from an entity by its rendered DL text. Read the entity first "
                                + "with ontology_entity and pass the exact axiom string shown there.",
                        schema(Map.of(
                                "iri", Map.of("type", "string", "description", "IRI of the entity that owns the axiom."),
                                "text", Map.of("type", "string", "description", "Exact rendered axiom text.")), List.of("iri", "text"))),
                tool("ontology_save",
                        "Save the open ontology to disk. Omit path to overwrite the file that was opened; "
                                + "pass a path to save a copy. Only call it when the user asks to save.",
                        schema(Map.of(
                                "path", Map.of("type", "string", "description", "Optional absolute file path.")), List.of())),
                tool("ontology_open",
                        "Open an ontology file from the local filesystem and replace the current workspace. "
                                + "Only call it when the user explicitly asks to open a specific file, because "
                                + "unsaved edits are discarded.",
                        schema(Map.of(
                                "path", Map.of("type", "string", "description", "Absolute path to an OWL, TTL, RDF, XML, or OFN file.")), List.of("path"))));
    }

    private static BetaManagedAgentsCustomToolParams tool(
            String name, String description, BetaManagedAgentsCustomToolInputSchema schema) {
        return BetaManagedAgentsCustomToolParams.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
    }

    private static BetaManagedAgentsCustomToolInputSchema schema(
            Map<String, Object> properties, List<String> required) {
        var builder = BetaManagedAgentsCustomToolInputSchema.Properties.builder();
        properties.forEach((name, definition) ->
                builder.putAdditionalProperty(name, JsonValue.from(definition)));
        var schema = BetaManagedAgentsCustomToolInputSchema.builder().properties(builder.build());
        required.forEach(schema::addRequired);
        return schema.build();
    }

    private void loadState() {
        try {
            if (!Files.isRegularFile(stateFile)) {
                return;
            }
            Map<String, String> state = mapper.readValue(
                    stateFile.toFile(), new TypeReference<Map<String, String>>() {
                    });
            agentId = state.get("agentId");
            environmentId = state.get("environmentId");
            sessionId = state.get("sessionId");
            sessionPath = state.get("sessionPath");
            configSignature = state.get("configSignature");
        } catch (Exception ignored) {
            // A corrupt cache only costs one extra agent/environment creation.
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Map<String, String> state = new LinkedHashMap<>();
            put(state, "agentId", agentId);
            put(state, "environmentId", environmentId);
            put(state, "sessionId", sessionId);
            put(state, "sessionPath", sessionPath);
            put(state, "configSignature", configSignature);
            mapper.writeValue(stateFile.toFile(), state);
        } catch (Exception ignored) {
            // The assistant still works without the cache.
        }
    }

    private static void put(Map<String, String> state, String key, String value) {
        if (value != null) {
            state.put(key, value);
        }
    }
}
