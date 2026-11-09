package studio.onto.example;

import org.semanticweb.owlapi.model.OWLEntity;
import studio.onto.engine.plugin.Plugin;

import java.util.List;
import java.util.Map;

/** Sample plugin: adds owl:deprecated to any entity. */
public class DeprecatePlugin implements Plugin {

    @Override
    public String name() {
        return "sample";
    }

    @Override
    public List<PluginAction> actions() {
        return List.of(new DeprecateAction());
    }

    private static final class DeprecateAction implements PluginAction {

        @Override
        public String name() {
            return "sample:deprecate";
        }

        @Override
        public String description() {
            return "Mark an entity as deprecated";
        }

        @Override
        public Map<String, String> params() {
            return Map.of("iri", "Entity to deprecate");
        }

        @Override
        public List<PluginChange> plan(PluginContext context, Map<String, String> params) {
            OWLEntity entity = context.entity(params.get("iri"));
            if (entity == null) {
                throw new IllegalArgumentException("Unknown entity: " + params.get("iri"));
            }
            return List.of(new PluginChange(true, context.dataFactory().getOWLAnnotationAssertionAxiom(
                    context.dataFactory().getOWLDeprecated(),
                    entity.getIRI(),
                    context.dataFactory().getOWLLiteral(true))));
        }
    }
}
