package studio.onto.engine.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Plugin that runs in its own JVM. The engine sends describe/plan requests over
 * stdio, so a plugin can crash, hang, or corrupt its own heap without taking the
 * workbench down.
 */
public final class RemotePlugin implements Plugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_ENTITIES = 2000;

    private final Path jar;
    private final Process process;
    private final BufferedWriter toHost;
    private final BufferedReader fromHost;
    private final String name;
    private final java.util.Set<String> permissions;
    private final List<PluginAction> actions = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public RemotePlugin(Path jar) throws Exception {
        this.jar = jar;
        this.process = new ProcessBuilder(
                javaBinary(), "-cp", System.getProperty("java.class.path"),
                "studio.onto.engine.plugin.PluginHost", jar.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        this.toHost = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromHost = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        Map<String, Object> described = request(Map.of("op", "describe"));
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) described.getOrDefault("plugins", List.of());
        if (plugins.isEmpty()) {
            throw new IllegalStateException("Plugin jar exposes no Plugin implementation");
        }
        Map<String, Object> first = plugins.get(0);
        this.name = String.valueOf(first.get("name"));
        this.permissions = new java.util.LinkedHashSet<>(
                (List<String>) first.getOrDefault("permissions", List.of("read")));
        for (Map<String, Object> action : (List<Map<String, Object>>) first.getOrDefault("actions", List.of())) {
            actions.add(new RemoteAction(
                    String.valueOf(action.get("name")),
                    String.valueOf(action.get("description")),
                    (Map<String, String>) action.getOrDefault("params", Map.of())));
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public java.util.Set<String> permissions() {
        return permissions;
    }

    @Override
    public List<PluginAction> actions() {
        return actions;
    }

    public void close() {
        try {
            request(Map.of("op", "quit"));
        } catch (Exception ignored) {
            // Already gone.
        }
        process.destroy();
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @SuppressWarnings("unchecked")
    private synchronized Map<String, Object> request(Map<String, Object> payload) throws Exception {
        toHost.write(MAPPER.writeValueAsString(payload));
        toHost.newLine();
        toHost.flush();

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                process.destroyForcibly();
            } catch (InterruptedException ignored) {
                // Request finished in time.
            }
        }, "plugin-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            String line = fromHost.readLine();
            if (line == null) {
                throw new IllegalStateException("Plugin process exited (" + jar.getFileName() + ")");
            }
            Map<String, Object> response = MAPPER.readValue(line, Map.class);
            if (response.containsKey("error")) {
                throw new IllegalArgumentException(String.valueOf(response.get("error")));
            }
            return response;
        } finally {
            watchdog.interrupt();
        }
    }

    private Map<String, Object> serializeContext(PluginContext context, String iri) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> entities = new ArrayList<>();
        for (OWLEntity entity : context.entities().stream().limit(MAX_ENTITIES).toList()) {
            entities.add(Map.of(
                    "iri", entity.getIRI().toString(),
                    "label", context.label(entity),
                    "kind", kindOf(entity)));
        }
        payload.put("entities", entities);
        OWLEntity selected = context.entity(iri);
        if (selected != null) {
            payload.put("entity", Map.of(
                    "iri", selected.getIRI().toString(),
                    "label", context.label(selected),
                    "kind", kindOf(selected),
                    "axioms", context.axiomsOf(selected)));
        }
        return payload;
    }

    private static String kindOf(OWLEntity entity) {
        if (entity.isOWLClass()) {
            return "class";
        }
        if (entity.isOWLObjectProperty()) {
            return "object property";
        }
        if (entity.isOWLDataProperty()) {
            return "data property";
        }
        if (entity.isOWLAnnotationProperty()) {
            return "annotation property";
        }
        return "individual";
    }

    private static OWLAxiom parseAxiom(String text) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParser()
                .parse(new StringDocumentSource("Ontology(" + text + ")"), ontology,
                        new org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration());
        return ontology.getAxioms().iterator().next();
    }

    private final class RemoteAction implements PluginAction {

        private final String actionName;
        private final String description;
        private final Map<String, String> params;

        RemoteAction(String actionName, String description, Map<String, String> params) {
            this.actionName = actionName;
            this.description = description;
            this.params = params;
        }

        @Override
        public String name() {
            return actionName;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, String> params() {
            return params;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<PluginChange> plan(PluginContext context, Map<String, String> values) {
            try {
                Map<String, Object> response = request(Map.of(
                        "op", "plan",
                        "action", actionName,
                        "params", values,
                        "context", serializeContext(context, values.get("iri"))));
                List<Map<String, Object>> changes =
                        (List<Map<String, Object>>) response.getOrDefault("changes", List.of());
                List<PluginChange> result = new ArrayList<>();
                for (Map<String, Object> change : changes) {
                    result.add(new PluginChange(
                            Boolean.TRUE.equals(change.get("add")),
                            parseAxiom(String.valueOf(change.get("axiom")))));
                }
                return result;
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException("Plugin " + actionName + " failed: " + error.getMessage());
            }
        }
    }
}
