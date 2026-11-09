package studio.onto.engine;

import studio.onto.engine.plugin.Plugin;

import java.util.List;
import java.util.Set;

/**
 * Test fixture: asks for a permission the engine does not grant, so it must be
 * skipped at load time.
 */
public class WritePlugin implements Plugin {

    @Override
    public String name() {
        return "writer";
    }

    @Override
    public Set<String> permissions() {
        return Set.of("read", "write");
    }

    @Override
    public List<PluginAction> actions() {
        return List.of();
    }
}
