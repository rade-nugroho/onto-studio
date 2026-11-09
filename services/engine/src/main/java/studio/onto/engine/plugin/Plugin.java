package studio.onto.engine.plugin;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.List;
import java.util.Map;

/**
 * Extension point for third-party actions. Implementations are discovered with
 * {@link java.util.ServiceLoader} on the engine classpath and from
 * {@code ~/.onto-studio/plugins/*.jar}.
 */
public interface Plugin {

    String name();

    List<PluginAction> actions();

    /** One declared action: metadata plus a pure planning function. */
    interface PluginAction {

        String name();

        String description();

        /** Parameter name to human readable description. */
        Map<String, String> params();

        /**
         * Returns the changes this action would make. Must not touch the
         * ontology; the engine applies the changes after the user confirms.
         */
        List<PluginChange> plan(PluginContext context, Map<String, String> params);
    }

    record PluginChange(boolean add, OWLAxiom axiom) {
    }

    /** Read-only view of the open ontology handed to plugins. */
    interface PluginContext {

        OWLOntology ontology();

        OWLDataFactory dataFactory();

        OWLEntity entity(String iri);

        String label(OWLEntity entity);
    }
}
