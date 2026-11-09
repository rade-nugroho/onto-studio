package studio.onto.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads configuration from the environment, falling back to .env.local files
 * next to the working directory or the engine jar. The desktop shell does not
 * forward its own environment to the engine, so this keeps local development
 * and the packaged app consistent.
 */
public final class EnvFile {

    private EnvFile() {
    }

    public static String get(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (Boolean.getBoolean("studio.onto.disableEnvFile")) {
            return null;
        }
        for (Path candidate : candidates()) {
            String found = read(candidate, name);
            if (found != null && !found.isBlank()) {
                return found;
            }
        }
        return null;
    }

    private static List<Path> candidates() {
        Path cwd = Path.of("").toAbsolutePath();
        List<Path> paths = new java.util.ArrayList<>();
        paths.add(cwd.resolve(".env.local"));
        paths.add(cwd.resolve("../.env.local"));
        paths.add(cwd.resolve("../../.env.local"));
        String home = System.getProperty("user.home");
        if (home != null) {
            paths.add(Path.of(home, ".onto-studio.env"));
        }
        try {
            Path jar = Path.of(EnvFile.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            paths.add(jar.resolve(".env.local"));
            paths.add(jar.resolve("../.env.local"));
        } catch (Exception ignored) {
            // No jar location available; the working-directory paths still apply.
        }
        return paths;
    }

    private static String read(Path file, String name) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(name + "=")) {
                    return trimmed.substring(name.length() + 1).trim().replaceAll("^\"|\"$", "");
                }
            }
        } catch (IOException ignored) {
            // Unreadable file: treat as absent.
        }
        return null;
    }
}
