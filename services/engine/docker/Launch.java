/**
 * Container entrypoint. Distroless has no shell, so this reads the runtime
 * environment and starts the engine. The token is never baked into the image.
 */
public final class Launch {

    private Launch() {
    }

    public static void main(String[] args) throws Exception {
        String token = System.getenv("ENGINE_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("ENGINE_TOKEN is not set. Refusing to start.");
            System.exit(1);
        }
        String port = System.getenv("ENGINE_PORT");
        if (port == null || port.isBlank()) {
            port = "7717";
        }
        String[] engineArgs = new String[args.length + 4];
        engineArgs[0] = "--port";
        engineArgs[1] = port.trim();
        engineArgs[2] = "--token";
        engineArgs[3] = token;
        System.arraycopy(args, 0, engineArgs, 4, args.length);
        studio.onto.engine.Main.main(engineArgs);
    }
}
