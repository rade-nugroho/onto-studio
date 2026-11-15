package studio.onto.engine.plugin;

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
                    RemotePlugin remote = new RemotePlugin(jar);
                    if (GRANTED.containsAll(remote.permissions())) {
                        plugins.add(remote);
                    } else {
                        remote.close();
                    }
                } catch (Exception error) {
                    // A broken plugin must not stop the engine from starting.
                    System.err.println("[engine] Failed to load plugin " + jar.getFileName() + ": " + error.getMessage());
                }
            }
        } catch (Exception error) {
            // Unreadable plugin directory: run with the built-in actions only.
            System.err.println("[engine] Failed to scan plugin directory " + dir + ": " + error.getMessage());
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
