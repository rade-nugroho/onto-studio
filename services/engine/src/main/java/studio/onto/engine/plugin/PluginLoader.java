package studio.onto.engine.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Discovers plugins from the engine classpath and from
 * {@code ~/.onto-studio/plugins/*.jar}.
 */
public final class PluginLoader {

    private PluginLoader() {
    }

    public static List<Plugin> load() {
        List<Plugin> plugins = new ArrayList<>();
        ServiceLoader.load(Plugin.class).forEach(plugins::add);

        Path dir = Path.of(System.getProperty("user.home", "."), ".onto-studio", "plugins");
        if (!Files.isDirectory(dir)) {
            return plugins;
        }
        try (Stream<Path> jars = Files.list(dir)) {
            for (Path jar : jars.filter(path -> path.toString().endsWith(".jar")).toList()) {
                try {
                    URLClassLoader loader = new URLClassLoader(
                            new URL[]{jar.toUri().toURL()}, Plugin.class.getClassLoader());
                    ServiceLoader.load(Plugin.class, loader).forEach(plugins::add);
                } catch (Exception ignored) {
                    // A broken plugin must not stop the engine from starting.
                }
            }
        } catch (Exception ignored) {
            // Unreadable plugin directory: run with the built-in actions only.
        }
        return plugins;
    }
}
