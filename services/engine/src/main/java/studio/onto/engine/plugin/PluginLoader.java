package studio.onto.engine.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers plugins from the engine classpath and from
 * {@code ~/.onto-studio/plugins/*.jar}, skipping anything that asks for
 * permissions the engine does not grant.
 */
public final class PluginLoader {

    public static final Set<String> GRANTED = Set.of("read");

    private PluginLoader() {
    }

    public static Path pluginsDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".onto-studio", "plugins");
    }

    public static List<Plugin> load() {
        List<Plugin> plugins = new ArrayList<>();
        addAll(ServiceLoader.load(Plugin.class), plugins);

        Path dir = pluginsDirectory();
        if (!Files.isDirectory(dir)) {
            return plugins;
        }
        try (Stream<Path> jars = Files.list(dir)) {
            for (Path jar : jars.filter(path -> path.toString().endsWith(".jar")).toList()) {
                try {
                    URLClassLoader loader = new URLClassLoader(
                            new URL[]{jar.toUri().toURL()}, Plugin.class.getClassLoader());
                    addAll(ServiceLoader.load(Plugin.class, loader), plugins);
                } catch (Exception ignored) {
                    // A broken plugin must not stop the engine from starting.
                }
            }
        } catch (Exception ignored) {
            // Unreadable plugin directory: run with the built-in actions only.
        }
        return plugins;
    }

    /**
     * Reads a provider at a time so one broken plugin (wrong bytecode version,
     * missing dependency, refused permission) is skipped instead of stopping
     * the engine.
     */
    private static void addAll(ServiceLoader<Plugin> loader, List<Plugin> plugins) {
        java.util.Iterator<Plugin> iterator = loader.iterator();
        for (int guard = 0; guard < 100; guard++) {
            Plugin plugin;
            try {
                if (!iterator.hasNext()) {
                    return;
                }
                plugin = iterator.next();
            } catch (java.util.ServiceConfigurationError error) {
                return;
            } catch (Exception error) {
                return;
            }
            if (GRANTED.containsAll(plugin.permissions())) {
                plugins.add(plugin);
            }
        }
    }
}
