package studio.onto.engine;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

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
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jsonMapper(new JavalinJackson());
        });

        app.before(ctx -> {
            // The desktop webview and the Vite dev server are different origins.
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Headers", "X-Engine-Token, Content-Type");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            if ("OPTIONS".equals(ctx.method().name())) {
                return;
            }
            if (!token.equals(ctx.header("X-Engine-Token"))) {
                throw new io.javalin.http.UnauthorizedResponse();
            }
        });
        app.options("/*", ctx -> ctx.status(204));

        app.get("/health", ctx -> ctx.json(Map.of("status", "ok", "version", "0.1.0")));
        app.post("/ontology/open", ctx -> workspace.open(ctx.bodyAsClass(OpenRequest.class), ctx));
        app.get("/hierarchy", ctx -> workspace.hierarchy(ctx.queryParam("parent"), ctx));
        app.get("/entity", ctx -> workspace.entity(ctx.queryParam("iri"), ctx));
        app.get("/search", ctx -> workspace.search(ctx.queryParam("q"), ctx));
        app.get("/graph", ctx -> workspace.graph(
                ctx.queryParam("root"),
                ctx.queryParam("depth") == null ? 2 : Integer.parseInt(ctx.queryParam("depth")),
                ctx));

        app.start("127.0.0.1", port);
        if (java.util.Arrays.asList(args).contains("--watch-parent")) {
            watchParent();
        }
        int boundPort = app.port();

        // The desktop shell reads this single line to learn where to connect.
        System.out.println("{\"port\":" + boundPort + ",\"token\":\"" + token + "\"}");
        System.out.flush();
    }

    public record OpenRequest(String path) {
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

    private static int intArg(String[] args, String name, int fallback) {
        String value = stringArg(args, name, null);
        return value == null ? fallback : Integer.parseInt(value);
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
