package studio.onto.engine.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Separate process that loads one plugin jar and answers describe/plan requests
 * over stdin/stdout. The engine never loads plugin code into its own JVM.
 */
public final class PluginHost {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static URLClassLoader keepAlive;

    private PluginHost() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: PluginHost <plugin.jar>");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);
        List<Plugin> plugins = new ArrayList<>();
        // Keep the loader open for the whole process: closing it would make the
        // plugin's own inner classes unloadable.
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, Plugin.class.getClassLoader());
            keepAlive = loader;
            ServiceLoader.load(Plugin.class, loader).forEach(plugins::add);
        } catch (Exception error) {
            System.out.println(MAPPER.writeValueAsString(Map.of("error", String.valueOf(error.getMessage()))));
            System.out.flush();
            return;
        }

        Map<String, Plugin.PluginAction> actions = new LinkedHashMap<>();
        for (Plugin plugin : plugins) {
            for (Plugin.PluginAction action : plugin.actions()) {
                actions.put(action.name(), action);
            }
        }

        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> response;
            try {
                Map<String, Object> request = MAPPER.readValue(line, Map.class);
                String op = String.valueOf(request.get("op"));
                switch (op) {
                    case "describe" -> {
                        response = new LinkedHashMap<>();
                        response.put("plugins", plugins.stream().map(plugin -> Map.of(
                                "name", plugin.name(),
                                "permissions", plugin.permissions(),
                                "actions", plugin.actions().stream().map(action -> Map.of(
                                        "name", action.name(),
                                        "description", action.description(),
                                        "params", action.params())).toList())).toList());
                    }
                    case "plan" -> {
                        String name = String.valueOf(request.get("action"));
                        Plugin.PluginAction action = actions.get(name);
                        if (action == null) {
                            response = Map.of("error", "Unknown action: " + name);
                        } else {
                            @SuppressWarnings("unchecked")
                            Map<String, String> params = (Map<String, String>) request.getOrDefault("params", Map.of());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> context = (Map<String, Object>) request.getOrDefault("context", Map.of());
                            List<Plugin.PluginChange> changes = action.plan(new HostContext(context), params);
                            response = Map.of("changes", changes.stream().map(change -> Map.of(
                                    "add", change.add(),
                                    "axiom", change.axiom().toString())).toList());
                        }
                    }
                    case "quit" -> {
                        return;
                    }
                    default -> response = Map.of("error", "Unknown op: " + op);
                }
            } catch (Exception error) {
                response = Map.of("error", String.valueOf(error.getMessage()));
            }
            out.println(MAPPER.writeValueAsString(response));
            out.flush();
        }
    }

    /** Context rebuilt from the payload the engine sends with each request. */
    private static final class HostContext implements Plugin.PluginContext {

        private final OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();
        private final Map<String, OWLEntity> entities = new LinkedHashMap<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, List<String>> axiomsByIri = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        HostContext(Map<String, Object> payload) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) payload.getOrDefault("entities", List.of());
            for (Map<String, Object> item : list) {
                String iri = String.valueOf(item.get("iri"));
                String kind = String.valueOf(item.get("kind"));
                OWLEntity entity = entityFor(iri, kind);
                if (entity != null) {
                    entities.put(iri, entity);
                    labels.put(iri, String.valueOf(item.getOrDefault("label", iri)));
                }
            }
            Map<String, Object> selected = (Map<String, Object>) payload.get("entity");
            if (selected != null && selected.get("iri") != null) {
                String selectedIri = String.valueOf(selected.get("iri"));
                List<String> axioms = (List<String>) selected.getOrDefault("axioms", List.of());
                axiomsByIri.put(selectedIri, List.copyOf(axioms));
            }
        }

        private OWLEntity entityFor(String iri, String kind) {
            IRI value = IRI.create(iri);
            return switch (kind) {
                case "object property" -> dataFactory.getOWLObjectProperty(value);
                case "data property" -> dataFactory.getOWLDataProperty(value);
                case "annotation property" -> dataFactory.getOWLAnnotationProperty(value);
                case "individual" -> dataFactory.getOWLNamedIndividual(value);
                default -> dataFactory.getOWLClass(value);
            };
        }

        @Override
        public OWLDataFactory dataFactory() {
            return dataFactory;
        }

        @Override
        public OWLEntity entity(String iri) {
            return iri == null ? null : entities.get(iri);
        }

        @Override
        public String label(OWLEntity entity) {
            return labels.getOrDefault(entity.getIRI().toString(), entity.getIRI().getShortForm());
        }

        @Override
        public List<OWLEntity> entities() {
            return List.copyOf(entities.values());
        }

        @Override
        public List<String> axiomsOf(OWLEntity entity) {
            if (entity == null) {
                return List.of();
            }
            return axiomsByIri.getOrDefault(entity.getIRI().toString(), List.of());
        }
    }
}
