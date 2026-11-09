package studio.onto.engine.plugin;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extension point for third-party actions. Implementations are discovered with
 * {@link java.util.ServiceLoader} on the engine classpath and from
 * {@code ~/.onto-studio/plugins/*.jar}.
 *
 * <p>Plugins never receive the ontology itself. They get a narrow read-only
 * context and return proposed changes; the engine applies them, so every edit
 * still flows through evidence and the audit trail.
 */
public interface Plugin {

    String name();

    List<PluginAction> actions();

    /**
     * Capabilities the plugin needs. Only {@code read} is supported today;
     * a plugin asking for anything else is skipped at load time.
     */
    default Set<String> permissions() {
        return Set.of("read");
    }

    /** One declared action: metadata plus a pure planning function. */
    interface PluginAction {

        String name();

        String description();

        /** Parameter name to human readable description. */
        Map<String, String> params();

        /**
         * Returns the changes this action would make. Must not mutate the
         * ontology; the engine verifies that and applies the changes after the
         * user confirms.
         */
        List<PluginChange> plan(PluginContext context, Map<String, String> params);
    }

    record PluginChange(boolean add, OWLAxiom axiom) {
    }

    /** Read-only view of the open ontology handed to plugins. */
    interface PluginContext {

        OWLDataFactory dataFactory();

        OWLEntity entity(String iri);

        String label(OWLEntity entity);

        /** All entities currently in the ontology, for lookups and pickers. */
        List<OWLEntity> entities();

        /** Axioms that mention this entity, rendered as text. */
        List<String> axiomsOf(OWLEntity entity);
    }
}
