package studio.onto.engine;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.json.JavalinJackson;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * Local engine process: serves the ontology API to the desktop UI.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = intArg(args, "--port", 0);
        String providedToken = stringArg(args, "--token", null);
        final String token = providedToken != null
                ? providedToken
                : Long.toHexString(new java.security.SecureRandom().nextLong());

        Workspace workspace = new Workspace();
        Assistant assistant = new Assistant(workspace);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            workspace.close();
            assistant.close();
        }, "engine-shutdown"));
        Javalin app = createApp(workspace, token, assistant);
        String bind = bindAddress();
        app.start(bind, port);
        assistant.setEndpoint("http://" + endpointHost(bind) + ":" + app.port(), token);
        if (java.util.Arrays.asList(args).contains("--watch-parent")) {
            watchParent();
        }
        int boundPort = app.port();

        // The desktop shell reads this single line to learn where to connect.
        System.out.println("{\"port\":" + boundPort + ",\"token\":\"" + token + "\"}");
        System.out.flush();
    }

    static Javalin createApp(Workspace workspace, String token) {
        return createApp(workspace, token, new Assistant(workspace));
    }

    static Javalin createApp(Workspace workspace, String token, Assistant assistant) {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jsonMapper(new JavalinJackson());
        });

        KeycloakAuth keycloak = new KeycloakAuth();
        app.before(ctx -> {
            // The desktop webview and the Vite dev server are local origins only.
            String origin = ctx.header("Origin");
            if (isAllowedOrigin(origin)) {
                ctx.header("Access-Control-Allow-Origin", origin);
                ctx.header("Vary", "Origin");
            }
            ctx.header("Access-Control-Allow-Headers", "Authorization, X-Engine-Token, Content-Type");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            if ("OPTIONS".equals(ctx.method().name()) || DeveloperDocs.isPublic(ctx)) {
                return;
            }
            if (keycloak.enabled()) {
                if (isAnonymousUnderKeycloak(ctx)) {
                    return;
                }
                keycloak.requireBearer(ctx);
                return;
            }
            String provided = ctx.header("X-Engine-Token");
            if (provided == null || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8))) {
                throw new io.javalin.http.UnauthorizedResponse();
            }
        });
        app.options("/*", ctx -> ctx.status(204));

        app.exception(HttpResponseException.class, (error, ctx) ->
                ctx.status(error.getStatus()).json(Map.of(
                        "error", error.getMessage() == null ? "Request error" : error.getMessage())));
        app.exception(IllegalArgumentException.class, (error, ctx) ->
                ctx.status(400).json(Map.of(
                        "error", error.getMessage() == null ? "Invalid request" : error.getMessage())));
        app.exception(Exception.class, (error, ctx) -> {
            System.err.println("[engine] Unhandled error on " + ctx.method() + " " + ctx.path() + ": " + error);
            error.printStackTrace(System.err);
            ctx.status(500).json(Map.of(
                    "error", error.getMessage() == null ? "Internal error" : error.getMessage()));
        });

        app.get("/health", ctx -> ctx.json(Map.of("status", "ok", "version", "0.1.0")));
        app.get("/openapi.json", ctx -> ctx.json(OpenApiDocument.document()));
        DeveloperDocs.register(app);
        app.post("/ontology/open", ctx -> workspace.open(requireBody(ctx, OpenRequest.class), ctx));
        app.post("/ontology/new", ctx -> workspace.newOntology(
                requireBody(ctx, SaveRequest.class).path(), ctx));
        app.post("/document/extract", ctx -> {
            String path = requireBody(ctx, OpenRequest.class).path();
            if (path == null || path.isBlank()) {
                ctx.status(400).json(Map.of("error", "Missing path"));
                return;
            }
            try {
                java.nio.file.Path file = java.nio.file.Path.of(path);
                if (!java.nio.file.Files.isRegularFile(file)) {
                    ctx.status(400).json(Map.of("error", "File not found: " + path));
                    return;
                }
                if (java.nio.file.Files.size(file) > DocumentText.MAX_BYTES) {
                    ctx.status(400).json(Map.of("error", "Document exceeds 20 MB limit"));
                    return;
                }
                String text = DocumentText.extract(file);
                ctx.json(Map.of(
                        "path", file.toAbsolutePath().toString(),
                        "chars", text.length(),
                        "text", text));
            } catch (Exception error) {
                ctx.status(400).json(Map.of("error", "Could not read document: " + error.getMessage()));
            }
        });
        app.post("/axiom/batch", ctx -> workspace.addAxiomsBatch(
                requireBody(ctx, AxiomTextRequest.class).text(), ctx));
        app.get("/hierarchy", ctx -> workspace.hierarchy(
                ctx.queryParam("parent"),
                "true".equals(ctx.queryParam("inferred")),
                ctx));
        app.get("/entity", ctx -> workspace.entity(ctx.queryParam("iri"), ctx));
        app.get("/search", ctx -> workspace.search(
                ctx.queryParam("q"), ctx.queryParam("kind"), ctx));
        app.post("/axiom/subclass", ctx -> workspace.addSubclass(requireBody(ctx, SubclassRequest.class), ctx));
        app.post("/axiom/subclass/remove", ctx -> workspace.removeSubclass(requireBody(ctx, SubclassRequest.class), ctx));
        app.post("/ontology/save", ctx -> workspace.save(requireBody(ctx, SaveRequest.class), ctx));
        app.post("/axiom/add-text", ctx -> workspace.addAxiomText(requireBody(ctx, AxiomTextRequest.class), ctx));
        app.post("/axiom/validate", ctx -> workspace.validateAxiomText(requireBody(ctx, AxiomTextRequest.class), ctx));
        app.get("/plugins", workspace::plugins);
        app.post("/plugins/install", ctx -> workspace.installPlugin(
                requireBody(ctx, PluginInstallRequest.class), ctx));
        app.get("/plugins/catalog", workspace::catalog);
        app.post("/plugins/catalog/add", ctx -> workspace.addCatalogEntry(
                requireBody(ctx, CatalogEntryRequest.class), ctx));
        app.post("/plugins/catalog/install", ctx -> workspace.installFromCatalog(
                requireBody(ctx, CatalogInstallRequest.class), ctx));
        app.post("/axiom/remove-text", ctx -> workspace.removeAxiomText(requireBody(ctx, AxiomTextRequest.class), ctx));
        app.post("/axiom/remove-rendered", ctx -> workspace.removeRenderedAxiom(requireBody(ctx, RenderedAxiomRequest.class), ctx));
        app.post("/undo", workspace::undo);
        app.post("/redo", workspace::redo);
        app.get("/history", workspace::history);
        app.get("/evidence", ctx -> workspace.evidence(ctx.queryParam("iri"), ctx));
        app.post("/action/preview", ctx -> workspace.previewAction(requireBody(ctx, ActionRequest.class), ctx));
        app.post("/action/commit", ctx -> workspace.commitAction(requireBody(ctx, ActionRequest.class), ctx));
        app.get("/audit", workspace::audit);
        app.get("/marking", ctx -> workspace.getMarking(ctx.queryParam("iri"), ctx));
        app.post("/marking/set", ctx -> workspace.setMarking(requireBody(ctx, MarkingRequest.class), ctx));
        app.post("/export", ctx -> workspace.export(requireBody(ctx, ExportRequest.class), ctx));
        app.post("/branch/create", ctx -> workspace.createBranch(requireBody(ctx, BranchRequest.class), ctx));
        app.post("/branch/switch", ctx -> workspace.switchBranch(requireBody(ctx, BranchRequest.class), ctx));
        app.get("/branch/list", workspace::listBranches);
        app.get("/branch/diff", ctx -> workspace.branchDiff(ctx.queryParam("name"), ctx));
        app.post("/branch/promote", ctx -> workspace.promoteBranch(requireBody(ctx, BranchRequest.class), ctx));
        app.get("/why", ctx -> workspace.why(
                ctx.queryParam("iri"), ctx.queryParam("axiom"), ctx));
        app.post("/assistant", ctx -> {
            AssistantRequest request = requireBody(ctx, AssistantRequest.class);
            assistant.answer(request.question(), request.entityIri(), ctx);
        });
        SystemOneClient systemOne = new SystemOneClient();
        app.post("/systemone/submit", systemOne::submit);
        app.get("/graph", ctx -> workspace.graph(
                ctx.queryParam("root"),
                parseDepth(ctx.queryParam("depth")),
                "true".equals(ctx.queryParam("inferred")),
                ctx));
        app.post("/reasoner/start", workspace::startReasoner);
        app.post("/reasoner/stop", workspace::stopReasoner);
        app.get("/reasoner/status", workspace::reasonerStatus);
        app.get("/connectors/mappings", ctx -> workspace.connectors().listMappings(ctx));
        app.get("/connectors/apps", ctx -> workspace.connectors().listApps(ctx));
        app.get("/connectors/connections", ctx -> workspace.connectors().listConnections(ctx));
        app.get("/connectors/actions", ctx -> workspace.connectors().listActions(ctx));
        app.post("/connectors/mapping/preview", ctx -> {
            ConnectorMappingRequest request = requireBody(ctx, ConnectorMappingRequest.class);
            workspace.connectors().previewMapping(request.toService(), ctx);
        });
        app.post("/connectors/mapping/commit", ctx -> {
            ConnectorMappingRequest request = requireBody(ctx, ConnectorMappingRequest.class);
            workspace.connectors().commitMapping(request.toService(), ctx);
        });
        return app;
    }

    public record ConnectorMappingRequest(
            String connectionName,
            String service,
            String actionId,
            String classIri,
            String newClassName,
            String idField,
            java.util.Map<String, String> fieldMap,
            java.util.Map<String, Object> actionInput,
            Integer sampleLimit) {
        studio.onto.engine.connector.ConnectorService.MappingRequest toService() {
            return new studio.onto.engine.connector.ConnectorService.MappingRequest(
                    connectionName, service, actionId, classIri, newClassName, idField, fieldMap,
                    actionInput, sampleLimit);
        }
    }

    public record OpenRequest(String path) {
    }

    public record SubclassRequest(String sub, String sup) {
    }

    public record SaveRequest(String path) {
    }

    public record AxiomTextRequest(String text) {
    }

    public record RenderedAxiomRequest(String iri, String text) {
    }

    public record AssistantRequest(String question, String entityIri) {
    }

    /**
     * {@code params} values are strings for the original actions. Structured
     * axioms also send numbers, arrays, and objects (class-expression trees).
     */
    public record ActionRequest(String action, Map<String, Object> params, String reason) {
    }

    public record BranchRequest(String name, String reason) {
    }

    public record MarkingRequest(String iri, String marking) {
    }

    public record ExportRequest(String path, Boolean includeRestricted) {
    }

    public record PluginInstallRequest(String url, String sha256) {
    }

    public record CatalogEntryRequest(String name, String url, String sha256) {
    }

    public record CatalogInstallRequest(String name) {
    }

    /**
     * The desktop shell keeps our stdin open. When the shell exits, the pipe
     * closes and we shut down instead of lingering as an orphan process.
     */
    private static void watchParent() {
        Thread watcher = new Thread(() -> {
            try {
                while (System.in.read() != -1) {
                    // Drain input; we only care about the pipe closing.
                }
            } catch (Exception ignored) {
                // Treat a broken pipe the same as a closed one.
            }
            System.exit(0);
        }, "parent-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean isAnonymousUnderKeycloak(Context ctx) {
        if (!"GET".equals(ctx.method().name())) {
            return false;
        }
        String path = ctx.path();
        return "/health".equals(path) || "/openapi.json".equals(path);
    }

    static boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        String normalized = origin.trim();
        if ("tauri://localhost".equalsIgnoreCase(normalized)
                || "https://tauri.localhost".equalsIgnoreCase(normalized)) {
            return true;
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
                return false;
            }
            if (uri.getUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(lowerHost)
                    || "127.0.0.1".equals(lowerHost)
                    || "[::1]".equals(lowerHost)
                    || "::1".equals(lowerHost);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static <T> T requireBody(Context ctx, Class<T> type) {
        String raw = ctx.body();
        if (raw.isBlank() || "null".equals(raw.trim())) {
            throw new IllegalArgumentException("Missing request body");
        }
        try {
            T body = ctx.bodyAsClass(type);
            if (body == null) {
                throw new IllegalArgumentException("Missing request body");
            }
            return body;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid JSON body: " + error.getMessage(), error);
        }
    }

    private static int parseDepth(String raw) {
        if (raw == null || raw.isBlank()) {
            return 2;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid depth: " + raw);
        }
    }

    /**
     * Listen address. Unset or blank {@code ENGINE_BIND} stays on 127.0.0.1.
     * Compose sets {@code ENGINE_BIND=0.0.0.0} so a published port can reach
     * the process. The request token is unchanged.
     */
    static String bindAddress() {
        return bindAddress(System.getenv("ENGINE_BIND"));
    }

    static String bindAddress(String configured) {
        if (configured == null || configured.isBlank()) {
            return "127.0.0.1";
        }
        return configured.trim();
    }

    /** Loopback is the client address when the process listens on every interface. */
    static String endpointHost(String bind) {
        if ("0.0.0.0".equals(bind) || "::".equals(bind) || "[::]".equals(bind)) {
            return "127.0.0.1";
        }
        return bind;
    }

    private static int intArg(String[] args, String name, int fallback) {
        String value = stringArg(args, name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid integer for " + name + ": " + value);
        }
    }

    private static String stringArg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
