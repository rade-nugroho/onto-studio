package studio.onto.engine;

import io.javalin.http.Context;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxEditorParser;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntaxObjectRenderer;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter;
import org.semanticweb.owlapi.util.OntologyAxiomPair;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import studio.onto.engine.connector.ConnectorException;
import studio.onto.engine.connector.ConnectorMapping;
import studio.onto.engine.connector.ConnectorService;
import studio.onto.engine.plugin.Plugin;
import studio.onto.engine.plugin.PluginLoader;
import studio.onto.engine.plugin.RemotePlugin;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Holds the active ontology, answers queries, and records edits for undo/redo.
 */
public final class Workspace implements Plugin.PluginContext, AutoCloseable {

    private static final int SEARCH_LIMIT = 50;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory dataFactory = manager.getOWLDataFactory();
    private final DLSyntaxObjectRenderer renderer = new DLSyntaxObjectRenderer();
    private final Deque<List<OWLOntologyChange>> undoStack = new ArrayDeque<>();
    private final Deque<List<OWLOntologyChange>> redoStack = new ArrayDeque<>();

    private OWLOntology ontology;
    private OWLOntology mainOntology;
    private OWLOntologyManager mainManager;
    private OWLOntologyManager activeManager;
    private final Map<String, Branch> branches = new LinkedHashMap<>();
    private String activeBranch;
    private EvidenceStore evidence;
    private MarkingStore markings;
    private ConnectorMapping connectorMappings;
    private final ConnectorService connectors = new ConnectorService();
    private final List<Plugin> plugins = new ArrayList<>(PluginLoader.load());
    private final PluginCatalog catalog = new PluginCatalog(PluginCatalog.defaultPath());
    private OWLReasoner reasoner;
    private File openedFile;
    private int savedDepth;

    public Workspace() {
        renderer.setShortFormProvider(new SimpleShortFormProvider());
        activeManager = manager;
    }

    @Override
    public synchronized void close() {
        stopReasonerInternal();
        closeRemotePlugins();
    }

    private void closeRemotePlugins() {
        for (Plugin plugin : plugins) {
            if (plugin instanceof RemotePlugin remote) {
                remote.close();
            }
        }
    }

    private record Branch(String name, OWLOntology ontology, OWLOntologyManager manager, String createdAt) {
    }

    public synchronized void open(Main.OpenRequest request, Context ctx) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing path"));
            return;
        }
        File file = new File(request.path());
        if (!file.isFile()) {
            ctx.status(400).json(Map.of("error", "File not found: " + request.path()));
            return;
        }
        manager.getOntologies().forEach(manager::removeOntology);
        ontology = null;
        try {
            ontology = manager.loadOntologyFromOntologyDocument(new FileDocumentSource(file));
        } catch (Exception error) {
            ctx.status(400).json(Map.of("error", "Could not open " + file + ": " + error.getMessage()));
            return;
        }
        initSessionState(file);
        evidence.record("import", "file", "", List.of(), 0.9, Map.of(
                "file", file.getAbsolutePath(),
                "count", ontology.getAxiomCount()));
        ctx.json(Map.of(
                "iri", ontology.getOntologyID().getOntologyIRI().map(IRI::toString).orElse(""),
                "path", file.getAbsolutePath(),
                "axiomCount", ontology.getAxiomCount(),
                "entityCount", ontology.getSignature().size(),
                "dirty", false,
                "canUndo", false,
                "canRedo", false));
    }

    private void initSessionState(File file) {
        stopReasonerInternal();
        openedFile = file;
        mainOntology = ontology;
        mainManager = manager;
        activeManager = manager;
        branches.clear();
        activeBranch = null;
        evidence = new EvidenceStore(EvidenceStore.defaultPath(file.toPath()));
        markings = new MarkingStore(MarkingStore.defaultPath(file.toPath()));
        connectorMappings = new ConnectorMapping(ConnectorMapping.defaultPath(file.toPath()));
        connectors.bindSession(ontology, dataFactory, connectorMappings, evidence,
                iri -> labelByIri(iri));
        undoStack.clear();
        redoStack.clear();
        savedDepth = 0;
    }

    private String labelByIri(String iri) {
        if (ontology == null || iri == null) {
            return iri;
        }
        OWLEntity entity = ontology.signature()
                .filter(candidate -> candidate.getIRI().toString().equals(iri))
                .findFirst()
                .orElse(null);
        return entity == null ? iri : label(entity);
    }

    public synchronized String currentPath() {
        return openedFile == null ? null : openedFile.getAbsolutePath();
    }

    public synchronized void newOntology(String path, Context ctx) {
        if (path == null || path.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing path"));
            return;
        }
        File file = new File(path);
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            manager.getOntologies().forEach(manager::removeOntology);
            ontology = manager.createOntology(IRI.create(file.toURI()));
            initSessionState(file);
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), IRI.create(file));
            ctx.json(Map.of(
                    "iri", ontology.getOntologyID().getOntologyIRI().map(IRI::toString).orElse(""),
                    "path", file.getAbsolutePath(),
                    "axiomCount", 0,
                    "entityCount", 0,
                    "dirty", false,
                    "canUndo", false,
                    "canRedo", false));
        } catch (Exception error) {
            ctx.status(400).json(Map.of("error", "Could not create " + file + ": " + error.getMessage()));
        }
    }

    public synchronized void addAxiomsBatch(String text, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (text == null || text.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing axiom text"));
            return;
        }
        List<OWLAxiom> axioms;
        try {
            axioms = parseFrames(text, true);
        } catch (Exception error) {
            ctx.status(400).json(Map.of("error", "Could not parse axioms: " + error.getMessage()));
            return;
        }
        if (axioms.isEmpty()) {
            ctx.status(400).json(Map.of("error", "No axiom found in text"));
            return;
        }
        apply(axioms.stream().map(axiom -> (OWLOntologyChange) new AddAxiom(ontology, axiom)).toList());
        Map<String, Object> response = new LinkedHashMap<>(state());
        response.put("added", axioms.stream().map(this::renderAxiom).toList());
        ctx.json(response);
    }

    public synchronized void hierarchy(String parentIri, boolean inferred, Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        boolean root = parentIri == null || parentIri.isBlank();
        IRI parent = root ? null : parseIri(parentIri, "parent");
        Stream<OWLClass> candidates;
        if (inferred && reasoner != null) {
            OWLClass start = root ? dataFactory.getOWLThing() : dataFactory.getOWLClass(parent);
            candidates = reasoner.getSubClasses(start, true).entities()
                    .filter(entity -> !entity.isOWLNothing());
        } else {
            candidates = root
                    ? ontology.classesInSignature().filter(this::isTopLevel)
                    : EntitySearcher.getSubClasses(dataFactory.getOWLClass(parent), ontology)
                            .filter(OWLClassExpression::isNamed)
                            .map(OWLClassExpression::asOWLClass);
        }
        List<Map<String, Object>> children = candidates
                .distinct()
                .sorted(Comparator.comparing(this::label, String.CASE_INSENSITIVE_ORDER))
                .map(child -> Map.<String, Object>of(
                        "iri", child.getIRI().toString(),
                        "label", label(child),
                        "hasChildren", hasChildren(child)))
                .toList();
        ctx.json(Map.of(
                "parent", root ? "root" : parentIri,
                "inferred", inferred && reasoner != null,
                "children", children));
    }

    public synchronized void entity(String iri, Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        if (iri == null || iri.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing iri"));
            return;
        }
        Map<String, Object> connectorIndividual;
        try {
            connectorIndividual = connectors.entityForMappedIndividual(iri);
        } catch (ConnectorException error) {
            ctx.status(503).json(Map.of(
                    "error", error.getMessage(),
                    "code", error.code(),
                    "gatewayAvailable", false));
            return;
        }
        if (!connectorIndividual.isEmpty()) {
            ctx.json(connectorIndividual);
            return;
        }
        OWLEntity entity = ontology.signature()
                .filter(candidate -> candidate.getIRI().toString().equals(iri))
                .findFirst()
                .orElse(null);
        if (entity == null) {
            ctx.status(404).json(Map.of("error", "Entity not found: " + iri));
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("iri", iri);
        response.put("label", label(entity));
        response.put("kind", kind(entity));
        List<Map<String, String>> domains = List.of();
        List<Map<String, String>> ranges = List.of();
        if (entity instanceof OWLObjectProperty objectProperty) {
            domains = namedClasses(EntitySearcher.getDomains(objectProperty, ontology));
            ranges = namedClasses(EntitySearcher.getRanges(objectProperty, ontology));
        } else if (entity instanceof OWLDataProperty dataProperty) {
            domains = namedClasses(EntitySearcher.getDomains(dataProperty, ontology));
            ranges = EntitySearcher.getRanges(dataProperty, ontology)
                    .map(range -> Map.of("iri", "", "label", renderer.render(range)))
                    .toList();
        }
        response.put("marking", markings == null ? "public" : markings.get(iri));
        response.put("usage", ontology.getReferencingAxioms(entity).size());
        response.put("superClasses", entity instanceof OWLClass cls ? relatedClasses(cls) : List.of());
        response.put("subClasses", entity instanceof OWLClass cls
                ? namedClasses(EntitySearcher.getSubClasses(cls, ontology))
                : List.of());
        response.put("domains", domains);
        response.put("ranges", ranges);
        response.put("types", entity instanceof OWLIndividual individual
                ? namedClasses(EntitySearcher.getTypes(individual, ontology))
                : List.of());
        response.put("annotations", annotationsOf(entity));
        response.put("axioms", axiomsOf(entity));
        if (entity instanceof OWLClass cls) {
            try {
                List<Map<String, Object>> remote = connectors.searchMappedIndividuals(
                        cls.getIRI().toString(), "", SEARCH_LIMIT);
                if (!remote.isEmpty()) {
                    response.put("connectorIndividuals", remote);
                }
            } catch (ConnectorException error) {
                response.put("connectorError", error.getMessage());
                response.put("gatewayAvailable", false);
            }
        }
        ctx.json(response);
    }

    public synchronized void search(String query, String kindFilter, Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        String needle = query == null ? "" : query.trim().toLowerCase();
        boolean hasKind = kindFilter != null && !kindFilter.isBlank();
        if (needle.isEmpty() && !hasKind) {
            ctx.json(Map.of("results", List.of()));
            return;
        }
        Stream<? extends OWLEntity> entities = Stream.of(
                ontology.classesInSignature(),
                ontology.objectPropertiesInSignature(),
                ontology.dataPropertiesInSignature(),
                ontology.annotationPropertiesInSignature(),
                ontology.individualsInSignature()).flatMap(stream -> stream);
        List<Map<String, Object>> results = new ArrayList<>(entities
                .filter(entity -> !hasKind || kind(entity).equals(kindFilter))
                .filter(entity -> needle.isEmpty()
                        || label(entity).toLowerCase().contains(needle)
                        || entity.getIRI().getShortForm().toLowerCase().contains(needle))
                .limit(SEARCH_LIMIT)
                .map(entity -> Map.<String, Object>of(
                        "iri", entity.getIRI().toString(),
                        "label", label(entity),
                        "kind", kind(entity)))
                .toList());
        if (connectorMappings != null) {
            for (ConnectorMapping.ClassMapping mapping : connectorMappings.all()) {
                if (hasKind && !"individual".equals(kindFilter) && !"class".equals(kindFilter)) {
                    continue;
                }
                try {
                    List<Map<String, Object>> remote = connectors.searchMappedIndividuals(
                            mapping.classIri(), needle, SEARCH_LIMIT - results.size());
                    for (Map<String, Object> row : remote) {
                        if (results.size() >= SEARCH_LIMIT) {
                            break;
                        }
                        if (results.stream().noneMatch(r -> row.get("iri").equals(r.get("iri")))) {
                            results.add(Map.of(
                                    "iri", row.get("iri"),
                                    "label", row.get("label"),
                                    "kind", "individual"));
                        }
                    }
                } catch (ConnectorException error) {
                    if (results.isEmpty()) {
                        ctx.status(503).json(Map.of(
                                "error", error.getMessage(),
                                "code", error.code(),
                                "gatewayAvailable", false));
                        return;
                    }
                }
            }
        }
        ctx.json(Map.of("results", results));
    }

    public ConnectorService connectors() {
        return connectors;
    }

    /**
     * Returns a slice of the class hierarchy as a graph: nodes carry their depth
     * so the UI can lay them out in layers.
     */
    public synchronized void graph(String rootIri, int depth, boolean inferred, Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        final int maxDepth = Math.max(1, Math.min(depth, 4));
        final int nodeLimit = 400;
        boolean root = rootIri == null || rootIri.isBlank();

        Deque<OWLClass> queue = new ArrayDeque<>();
        Map<String, Integer> depthByIri = new LinkedHashMap<>();
        if (root) {
            (inferred && reasoner != null
                    ? reasoner.getSubClasses(dataFactory.getOWLThing(), true).entities()
                            .filter(entity -> !entity.isOWLNothing())
                    : ontology.classesInSignature().filter(this::isTopLevel))
                    .limit(30)
                    .forEach(cls -> {
                        depthByIri.put(cls.getIRI().toString(), 0);
                        queue.add(cls);
                    });
        } else {
            OWLClass start = dataFactory.getOWLClass(parseIri(rootIri, "root"));
            depthByIri.put(start.getIRI().toString(), 0);
            queue.add(start);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        while (!queue.isEmpty() && nodes.size() < nodeLimit) {
            OWLClass cls = queue.removeFirst();
            String iri = cls.getIRI().toString();
            int nodeDepth = depthByIri.get(iri);
            nodes.add(Map.of(
                    "iri", iri,
                    "label", label(cls),
                    "depth", nodeDepth));
            if (nodeDepth >= maxDepth) {
                continue;
            }
            childrenOf(cls, inferred)
                    .forEach(child -> {
                        String childIri = child.getIRI().toString();
                        edges.add(Map.of("source", iri, "target", childIri));
                        if (!depthByIri.containsKey(childIri) && nodes.size() < nodeLimit) {
                            depthByIri.put(childIri, nodeDepth + 1);
                            queue.add(child);
                        }
                    });
        }
        ctx.json(Map.of("nodes", nodes, "edges", edges));
    }

    public synchronized void startReasoner(Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        stopReasonerInternal();
        reasoner = new StructuralReasonerFactory().createReasoner(ontology);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        ctx.json(Map.of("running", true, "name", reasoner.getReasonerName()));
    }

    public synchronized void stopReasoner(Context ctx) {
        stopReasonerInternal();
        ctx.json(Map.of("running", false));
    }

    public synchronized void reasonerStatus(Context ctx) {
        ctx.json(Map.of(
                "running", reasoner != null,
                "name", reasoner == null ? "" : reasoner.getReasonerName()));
    }

    private void stopReasonerInternal() {
        if (reasoner != null) {
            reasoner.dispose();
            reasoner = null;
        }
    }

    private Stream<OWLClass> childrenOf(OWLClass cls, boolean inferred) {
        if (inferred && reasoner != null) {
            return reasoner.getSubClasses(cls, true).entities()
                    .filter(entity -> !entity.isOWLNothing());
        }
        return EntitySearcher.getSubClasses(cls, ontology)
                .filter(OWLClassExpression::isNamed)
                .map(OWLClassExpression::asOWLClass)
                .distinct();
    }

    public synchronized void addSubclass(Main.SubclassRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.sub() == null || request.sub().isBlank()
                || request.sup() == null || request.sup().isBlank()) {
            ctx.status(400).json(Map.of("error", "Both sub and sup IRIs are required"));
            return;
        }
        OWLClass sub = dataFactory.getOWLClass(parseIri(request.sub(), "sub"));
        OWLClass sup = dataFactory.getOWLClass(parseIri(request.sup(), "sup"));
        apply(List.of(new AddAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(sub, sup))));
        ctx.json(state());
    }

    public synchronized void removeSubclass(Main.SubclassRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.sub() == null || request.sub().isBlank()
                || request.sup() == null || request.sup().isBlank()) {
            ctx.status(400).json(Map.of("error", "Both sub and sup IRIs are required"));
            return;
        }
        OWLClass sub = dataFactory.getOWLClass(parseIri(request.sub(), "sub"));
        OWLClass sup = dataFactory.getOWLClass(parseIri(request.sup(), "sup"));
        apply(List.of(new RemoveAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(sub, sup))));
        ctx.json(state());
    }

    /**
     * Adds axioms written in Manchester frame syntax, for example:
     * {@code Class: Pizza  SubClassOf: Food}.
     */
    public synchronized void addAxiomText(Main.AxiomTextRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.text() == null || request.text().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing axiom text"));
            return;
        }
        List<OWLAxiom> axioms;
        try {
            axioms = parseFrames(request.text(), true);
        } catch (Exception error) {
            ctx.status(400).json(Map.of("error", "Could not parse axiom: " + error.getMessage()));
            return;
        }
        if (axioms.isEmpty()) {
            ctx.status(400).json(Map.of("error", "No axiom found in text"));
            return;
        }
        apply(axioms.stream().map(axiom -> (OWLOntologyChange) new AddAxiom(ontology, axiom)).toList());
        Map<String, Object> response = new LinkedHashMap<>(state());
        response.put("added", axioms.stream().map(this::renderAxiom).toList());
        ctx.json(response);
    }

    public synchronized void removeAxiomText(Main.AxiomTextRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.text() == null || request.text().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing axiom text"));
            return;
        }
        List<OWLAxiom> axioms;
        try {
            axioms = parseFrames(request.text(), false);
        } catch (Exception error) {
            ctx.status(400).json(Map.of("error", "Could not parse axiom: " + error.getMessage()));
            return;
        }
        List<OWLOntologyChange> changes = axioms.stream()
                .filter(ontology::containsAxiom)
                .map(axiom -> (OWLOntologyChange) new RemoveAxiom(ontology, axiom))
                .toList();
        if (changes.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Axiom not found in ontology"));
            return;
        }
        apply(changes);
        ctx.json(state());
    }

    /**
     * Removes the axiom whose DL rendering matches the text shown in the UI.
     */
    public synchronized void removeRenderedAxiom(Main.RenderedAxiomRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.iri() == null || request.iri().isBlank()
                || request.text() == null || request.text().isBlank()) {
            ctx.status(400).json(Map.of("error", "Both iri and text are required"));
            return;
        }
        OWLEntity entity = ontology.signature()
                .filter(candidate -> candidate.getIRI().toString().equals(request.iri()))
                .findFirst()
                .orElse(null);
        if (entity == null) {
            ctx.status(404).json(Map.of("error", "Entity not found: " + request.iri()));
            return;
        }
        OWLAxiom match = ontology.getReferencingAxioms(entity).stream()
                .filter(axiom -> !axiom.isAnnotationAxiom())
                .filter(axiom -> renderer.render(axiom).equals(request.text()))
                .findFirst()
                .orElse(null);
        if (match == null) {
            ctx.status(404).json(Map.of("error", "Axiom not found: " + request.text()));
            return;
        }
        apply(List.of(new RemoveAxiom(ontology, match)));
        ctx.json(state());
    }

    public synchronized void undo(Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (undoStack.isEmpty()) {
            ctx.status(409).json(Map.of("error", "Nothing to undo"));
            return;
        }
        List<OWLOntologyChange> changes = undoStack.pop();
        List<OWLOntologyChange> inverseChanges = inverse(changes);
        activeManager.applyChanges(inverseChanges);
        recordEvidence(inverseChanges, "undo");
        redoStack.push(changes);
        ctx.json(state());
    }

    public synchronized void redo(Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (redoStack.isEmpty()) {
            ctx.status(409).json(Map.of("error", "Nothing to redo"));
            return;
        }
        List<OWLOntologyChange> changes = redoStack.pop();
        activeManager.applyChanges(changes);
        recordEvidence(changes, "redo");
        undoStack.push(changes);
        ctx.json(state());
    }

    public synchronized void history(Context ctx) {
        ctx.json(state());
    }

    public synchronized void save(Main.SaveRequest request, Context ctx) throws Exception {
        if (!requireOntology(ctx)) {
            return;
        }
        if (activeBranch != null) {
            ctx.status(409).json(Map.of("error", "Switch to main before saving; promote the branch instead"));
            return;
        }
        File target = request == null || request.path() == null || request.path().isBlank()
                ? openedFile
                : new File(request.path());
        if (target == null) {
            ctx.status(400).json(Map.of("error", "No save path"));
            return;
        }
        org.semanticweb.owlapi.model.OWLDocumentFormat format = manager.getOntologyFormat(ontology);
        manager.saveOntology(ontology, format != null ? format : new RDFXMLDocumentFormat(), IRI.create(target));
        openedFile = target;
        savedDepth = undoStack.size();
        ctx.json(state());
    }

    /**
     * Deterministic ontology summary for the assistant: identity, size,
     * top-level classes, and (optionally) the axioms of one entity.
     */
    public synchronized String ontologyContext(String entityIri) {
        if (ontology == null) {
            return "No ontology is open.";
        }
        StringBuilder context = new StringBuilder();
        context.append("Ontology: ")
                .append(ontology.getOntologyID().getOntologyIRI().map(IRI::toString).orElse("anonymous"))
                .append("\nAxioms: ").append(ontology.getAxiomCount())
                .append("\nEntities: ").append(ontology.getSignature().size())
                .append("\nTop-level classes: ");
        List<String> topLevel = ontology.classesInSignature().filter(this::isTopLevel)
                .limit(20).map(cls -> label(cls)).toList();
        context.append(String.join(", ", topLevel));
        if (entityIri != null && !entityIri.isBlank()) {
            ontology.signature()
                    .filter(entity -> entity.getIRI().toString().equals(entityIri))
                    .findFirst()
                    .ifPresent(entity -> {
                        context.append("\n\nSelected entity: ").append(label(entity))
                                .append(" (").append(kind(entity)).append(")")
                                .append("\nIts axioms:");
                        axiomsOf(entity).forEach(axiom -> context.append("\n- ").append(axiom));
                    });
        }
        return context.toString();
    }

    private List<OWLAxiom> parseFrames(String text, boolean allowNew) {
        ManchesterOWLSyntaxEditorParser parser = new ManchesterOWLSyntaxEditorParser(dataFactory, text);
        parser.setDefaultOntology(ontology);
        var prefixes = parser.getPrefixManager();
        ontology.getOntologyID().getOntologyIRI().ifPresent(iri ->
                prefixes.setDefaultPrefix(iri.toString().endsWith("#") ? iri.toString() : iri + "#"));
        prefixes.setPrefix("rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        prefixes.setPrefix("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        prefixes.setPrefix("owl:", "http://www.w3.org/2002/07/owl#");
        prefixes.setPrefix("xsd:", "http://www.w3.org/2001/XMLSchema#");
        prefixes.setPrefix("skos:", "http://www.w3.org/2004/02/skos/core#");
        prefixes.setPrefix("dc:", "http://purl.org/dc/elements/1.1/");
        BidirectionalShortFormProviderAdapter shortForms =
                new BidirectionalShortFormProviderAdapter(manager, Set.of(ontology), new SimpleShortFormProvider());
        parser.setOWLEntityChecker(allowNew
                ? new AutoEntityChecker(new ShortFormEntityChecker(shortForms), ontology, dataFactory)
                : new ShortFormEntityChecker(shortForms));
        return parser.parseFrames().stream()
                .filter(pair -> pair.getOntology() == null || pair.getOntology().equals(ontology))
                .map(OntologyAxiomPair::getAxiom)
                .map(this::literalizeQuotedAnnotation)
                .distinct()
                .toList();
    }

    /**
     * The Manchester frame parser turns a quoted annotation value into an IRI
     * relative to the default prefix. Convert those back into literals so
     * {@code Annotations: rdfs:comment "text"} behaves as written.
     */
    private OWLAxiom literalizeQuotedAnnotation(OWLAxiom axiom) {
        if (!(axiom instanceof OWLAnnotationAssertionAxiom assertion)) {
            return axiom;
        }
        var value = assertion.getValue();
        if (!value.isIRI()) {
            return axiom;
        }
        String iri = value.asIRI().get().toString();
        int start = iri.indexOf('"');
        int end = iri.lastIndexOf('"');
        if (start < 0 || end <= start) {
            return axiom;
        }
        String text = iri.substring(start + 1, end);
        return dataFactory.getOWLAnnotationAssertionAxiom(
                assertion.getProperty(), assertion.getSubject(), dataFactory.getOWLLiteral(text));
    }

    /** DL rendering leaves annotation axioms blank; fall back to functional syntax. */
    private String renderAxiom(OWLAxiom axiom) {
        String rendered = renderer.render(axiom);
        return rendered == null || rendered.isBlank() ? axiom.toString() : rendered;
    }

    private void apply(List<OWLOntologyChange> changes) {
        apply(changes, "manual");
    }

    private void apply(List<OWLOntologyChange> changes, String source) {
        activeManager.applyChanges(changes);
        recordEvidence(changes, source);
        if (reasoner != null) {
            reasoner.flush();
        }
        undoStack.push(List.copyOf(changes));
        redoStack.clear();
    }

    private void recordEvidence(List<OWLOntologyChange> changes, String source) {
        if (evidence == null) {
            return;
        }
        for (OWLOntologyChange change : changes) {
            if (change instanceof AddAxiom add) {
                evidence.record("add", source, renderer.render(add.getAxiom()), entitiesOf(add.getAxiom()), 1.0, null);
            } else if (change instanceof RemoveAxiom remove) {
                evidence.record("remove", source, renderer.render(remove.getAxiom()), entitiesOf(remove.getAxiom()), 1.0, null);
            }
        }
    }

    private static List<String> entitiesOf(OWLAxiom axiom) {
        return axiom.signature().map(entity -> entity.getIRI().toString()).toList();
    }

    /**
     * Plans a declared action without touching the ontology. The caller shows
     * the summary to the user, who then commits or discards it.
     */
    public synchronized void previewAction(Main.ActionRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.action() == null || request.action().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing action"));
            return;
        }
        Plan plan;
        try {
            plan = plan(request);
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
            return;
        }
        ctx.json(Map.of(
                "action", plan.name,
                "summary", plan.summary,
                "changes", plan.changes.stream().map(Workspace::describe).toList()));
    }

    public synchronized void commitAction(Main.ActionRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.action() == null || request.action().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing action"));
            return;
        }
        Plan plan;
        try {
            plan = plan(request);
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
            return;
        }
        apply(plan.changes, "action:" + plan.name);
        if (evidence != null) {
            evidence.record("action", "action:" + plan.name, "", plan.entities, 1.0, Map.of(
                    "actionName", plan.name,
                    "reason", request.reason() == null ? "" : request.reason(),
                    "changes", plan.summary,
                    "params", plan.params));
        }
        Map<String, Object> response = new LinkedHashMap<>(state());
        response.put("action", plan.name);
        response.put("summary", plan.summary);
        ctx.json(response);
    }

    public synchronized void audit(Context ctx) {
        ctx.json(Map.of("rows", evidence == null ? List.of() : evidence.recentActions(50)));
    }

    public synchronized void createBranch(Main.BranchRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing branch name"));
            return;
        }
        if (branches.containsKey(name)) {
            ctx.status(409).json(Map.of("error", "Branch already exists: " + name));
            return;
        }
        OWLOntologyManager branchManager = OWLManager.createOWLOntologyManager();
        OWLOntology copy;
        try {
            copy = branchManager.createOntology(ontology.getAxioms(), IRI.create(branchIri(name)));
        } catch (Exception error) {
            ctx.status(500).json(Map.of("error", "Could not create branch: " + error.getMessage()));
            return;
        }
        branches.put(name, new Branch(name, copy, branchManager, java.time.Instant.now().toString()));
        ctx.json(Map.of("name", name, "axioms", copy.getAxiomCount()));
    }

    public synchronized void switchBranch(Main.BranchRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || "main".equals(name)) {
            ontology = mainOntology;
            activeManager = mainManager;
            activeBranch = null;
        } else {
            Branch branch = branches.get(name);
            if (branch == null) {
                ctx.status(404).json(Map.of("error", "Unknown branch: " + name));
                return;
            }
            ontology = branch.ontology();
            activeManager = branch.manager();
            activeBranch = name;
        }
        undoStack.clear();
        redoStack.clear();
        ctx.json(Map.of(
                "branch", activeBranch == null ? "main" : activeBranch,
                "axioms", ontology.getAxiomCount()));
    }

    public synchronized void listBranches(Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Map.of("name", "main", "axioms", mainOntology.getAxiomCount(),
                "active", activeBranch == null));
        for (Branch branch : branches.values()) {
            list.add(Map.of(
                    "name", branch.name(),
                    "axioms", branch.ontology().getAxiomCount(),
                    "createdAt", branch.createdAt(),
                    "active", branch.name().equals(activeBranch)));
        }
        ctx.json(Map.of("branches", list));
    }

    /**
     * Semantic diff between a branch and main: asserted axioms that would be
     * added or removed, plus inferred subsumptions that appear or disappear.
     */
    public synchronized void branchDiff(String name, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (name == null || name.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing branch name"));
            return;
        }
        Branch branch = branches.get(name);
        if (branch == null) {
            ctx.status(404).json(Map.of("error", "Unknown branch: " + name));
            return;
        }
        Map<String, String> mainAxioms = renderedAxioms(mainOntology);
        Map<String, String> branchAxioms = renderedAxioms(branch.ontology());

        List<String> added = branchAxioms.keySet().stream()
                .filter(key -> !mainAxioms.containsKey(key)).sorted().toList();
        List<String> removed = mainAxioms.keySet().stream()
                .filter(key -> !branchAxioms.containsKey(key)).sorted().toList();

        Set<String> mainInferred = inferredSubsumptions(mainOntology);
        Set<String> branchInferred = inferredSubsumptions(branch.ontology());
        List<String> inferredAdded = branchInferred.stream()
                .filter(item -> !mainInferred.contains(item)).sorted().toList();
        List<String> inferredRemoved = mainInferred.stream()
                .filter(item -> !branchInferred.contains(item)).sorted().toList();

        ctx.json(Map.of(
                "branch", name,
                "added", added,
                "removed", removed,
                "inferredAdded", inferredAdded,
                "inferredRemoved", inferredRemoved));
    }

    public synchronized void promoteBranch(Main.BranchRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        String name = request == null || request.name() == null ? "" : request.name().trim();
        Branch branch = branches.get(name);
        if (branch == null) {
            ctx.status(404).json(Map.of("error", "Unknown branch: " + name));
            return;
        }
        Map<String, OWLAxiom> mainAxioms = mainOntology.getAxioms().stream()
                .collect(java.util.stream.Collectors.toMap(Workspace::axiomKey, axiom -> axiom, (a, b) -> a));
        Map<String, OWLAxiom> branchAxioms = branch.ontology().getAxioms().stream()
                .collect(java.util.stream.Collectors.toMap(Workspace::axiomKey, axiom -> axiom, (a, b) -> a));

        List<OWLOntologyChange> changes = new ArrayList<>();
        branchAxioms.forEach((key, axiom) -> {
            if (!mainAxioms.containsKey(key)) {
                changes.add(new AddAxiom(mainOntology, axiom));
            }
        });
        mainAxioms.forEach((key, axiom) -> {
            if (!branchAxioms.containsKey(key)) {
                changes.add(new RemoveAxiom(mainOntology, axiom));
            }
        });
        if (changes.isEmpty()) {
            ctx.json(Map.of("promoted", name, "changes", 0));
            return;
        }
        mainManager.applyChanges(changes);
        recordEvidence(changes, "promote:" + name);
        if (evidence != null) {
            evidence.record("action", "promote:" + name, "", List.of(), 1.0, Map.of(
                    "actionName", "promote",
                    "reason", request.reason() == null ? "" : request.reason(),
                    "changes", changes.stream().map(Workspace::describe).toList(),
                    "branch", name));
        }
        branches.remove(name);
        ontology = mainOntology;
        activeManager = mainManager;
        activeBranch = null;
        undoStack.clear();
        redoStack.clear();
        ctx.json(Map.of("promoted", name, "changes", changes.size()));
    }

    private String branchIri(String name) {
        String base = ontology.getOntologyID().getOntologyIRI().map(IRI::toString).orElse("urn:onto-studio");
        return base + "#branch-" + name.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private static Map<String, String> renderedAxioms(OWLOntology source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.getAxioms().forEach(axiom -> result.put(axiomKey(axiom), axiom.toString()));
        return result;
    }

    private static String axiomKey(OWLAxiom axiom) {
        return axiom.toString();
    }

    /** Inferred named subsumptions, capped so diffing stays responsive. */
    private Set<String> inferredSubsumptions(OWLOntology source) {
        Set<String> result = new java.util.HashSet<>();
        OWLReasoner structural = new StructuralReasonerFactory().createReasoner(source);
        try {
            structural.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            source.classesInSignature().limit(150).forEach(cls ->
                    structural.getSubClasses(cls, true).entities()
                            .filter(sub -> !sub.isOWLNothing() && !sub.equals(cls))
                            .forEach(sub -> result.add(label(sub) + " ⊑ " + label(cls))));
        } finally {
            structural.dispose();
        }
        return result;
    }

    public synchronized void plugins(Context ctx) {
        ctx.json(Map.of("plugins", plugins.stream().map(plugin -> Map.of(
                "name", plugin.name(),
                "permissions", plugin.permissions(),
                "actions", plugin.actions().stream().map(action -> Map.of(
                        "name", action.name(),
                        "description", action.description(),
                        "params", action.params())).toList())).toList()));
    }

    /**
     * Installs a plugin jar from a URL after verifying its SHA-256 digest, then
     * reloads the plugin list. Distribution stays explicit: the caller pins the
     * exact artifact it trusts.
     */
    public synchronized void installPlugin(Main.PluginInstallRequest request, Context ctx) {
        if (request == null) {
            ctx.status(400).json(Map.of("error", "Missing request body"));
            return;
        }
        installFromUrl(request.url(), request.sha256(), ctx);
    }

    public synchronized void catalog(Context ctx) {
        ctx.json(Map.of("entries", catalog.all().entrySet().stream().map(entry -> Map.of(
                "name", entry.getKey(),
                "url", entry.getValue().url(),
                "sha256", entry.getValue().sha256(),
                "valid", catalog.verify(entry.getKey(), entry.getValue()))).toList()));
    }

    public synchronized void addCatalogEntry(Main.CatalogEntryRequest request, Context ctx) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.url() == null || request.url().isBlank()
                || request.sha256() == null || request.sha256().isBlank()) {
            ctx.status(400).json(Map.of("error", "name, url and sha256 are required"));
            return;
        }
        try {
            PluginCatalog.Entry entry = catalog.add(request.name(), request.url(), request.sha256());
            ctx.json(Map.of("name", request.name(), "signature", entry.signature()));
        } catch (IllegalStateException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
        }
    }

    public synchronized void installFromCatalog(Main.CatalogInstallRequest request, Context ctx) {
        String name = request == null || request.name() == null ? "" : request.name().trim();
        PluginCatalog.Entry entry = catalog.get(name);
        if (entry == null) {
            ctx.status(404).json(Map.of("error", "Unknown catalog entry: " + name));
            return;
        }
        if (!catalog.verify(name, entry)) {
            ctx.status(400).json(Map.of("error", "Catalog signature is invalid for " + name));
            return;
        }
        installFromUrl(entry.url(), entry.sha256(), ctx);
    }

    private void installFromUrl(String url, String sha256Value, Context ctx) {
        if (url == null || url.isBlank() || sha256Value == null || sha256Value.isBlank()) {
            ctx.status(400).json(Map.of("error", "Both url and sha256 are required"));
            return;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", "Invalid plugin URL: " + error.getMessage()));
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            ctx.status(400).json(Map.of("error", "Only http and https URLs are supported"));
            return;
        }
        byte[] bytes;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                ctx.status(400).json(Map.of("error", "Download failed: HTTP " + response.statusCode()));
                return;
            }
            bytes = response.body();
        } catch (Exception error) {
            ctx.status(400).json(Map.of("error", "Download failed: " + error.getMessage()));
            return;
        }
        if (bytes.length > 5 * 1024 * 1024) {
            ctx.status(400).json(Map.of("error", "Plugin jar is larger than 5 MB"));
            return;
        }
        String digest = sha256(bytes);
        if (!digest.equalsIgnoreCase(sha256Value.trim())) {
            ctx.status(400).json(Map.of("error", "Checksum mismatch",
                    "expected", sha256Value.trim(), "actual", digest));
            return;
        }
        String fileName = safePluginFileName(uri.getPath(), digest);
        try {
            Path dir = PluginLoader.pluginsDirectory().toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName).normalize();
            if (!target.startsWith(dir)) {
                ctx.status(400).json(Map.of("error", "Invalid plugin filename"));
                return;
            }
            AtomicFiles.writeBytes(target, bytes);
            closeRemotePlugins();
            plugins.clear();
            plugins.addAll(PluginLoader.load());
            ctx.json(Map.of("installed", target.getFileName().toString(), "plugins", plugins.size()));
        } catch (Exception error) {
            System.err.println("[engine] Could not install plugin: " + error.getMessage());
            ctx.status(500).json(Map.of("error", "Could not install plugin: " + error.getMessage()));
        }
    }

    private static String safePluginFileName(String rawPath, String digest) {
        String candidate = "";
        if (rawPath != null && !rawPath.isBlank()) {
            Path pathName = Path.of(rawPath).getFileName();
            if (pathName != null) {
                candidate = pathName.toString();
            }
        }
        String sanitized = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized) || !sanitized.endsWith(".jar")) {
            return "plugin-" + digest.substring(0, 8) + ".jar";
        }
        return sanitized;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** Parses axiom text without applying it, so the UI can validate live. */
    public synchronized void validateAxiomText(Main.AxiomTextRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        String text = request == null || request.text() == null ? "" : request.text();
        if (text.isBlank()) {
            ctx.json(Map.of("ok", false, "error", "Axiom text is empty", "axioms", List.of()));
            return;
        }
        try {
            List<OWLAxiom> axioms = parseFrames(text, false);
            ctx.json(Map.of(
                    "ok", !axioms.isEmpty(),
                    "axioms", axioms.stream().map(renderer::render).filter(r -> !r.isBlank()).toList()));
        } catch (Exception error) {
            ctx.json(Map.of("ok", false, "error", String.valueOf(error.getMessage()), "axioms", List.of()));
        }
    }

    public synchronized void getMarking(String iri, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (iri == null || iri.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing iri"));
            return;
        }
        ctx.json(Map.of("iri", iri, "marking", markings == null ? "public" : markings.get(iri)));
    }

    public synchronized void setMarking(Main.MarkingRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.iri() == null || request.iri().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing iri"));
            return;
        }
        if (request.marking() == null || request.marking().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing marking"));
            return;
        }
        try {
            markings.set(request.iri(), request.marking());
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
            return;
        }
        ctx.json(Map.of("iri", request.iri(), "marking", markings.get(request.iri())));
    }

    /**
     * Writes a copy of the ontology without axioms that mention restricted
     * entities, unless the caller explicitly asks to include them.
     */
    public synchronized void export(Main.ExportRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (request == null || request.path() == null || request.path().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing export path"));
            return;
        }
        boolean includeRestricted = Boolean.TRUE.equals(request.includeRestricted());
        List<OWLAxiom> kept = new ArrayList<>();
        int skipped = 0;
        for (OWLAxiom axiom : ontology.getAxioms()) {
            boolean touchesRestricted = axiom.signature()
                    .anyMatch(entity -> markings != null && markings.isRestricted(entity.getIRI().toString()));
            if (touchesRestricted && !includeRestricted) {
                skipped++;
            } else {
                kept.add(axiom);
            }
        }
        OWLOntologyManager exportManager = OWLManager.createOWLOntologyManager();
        try {
            OWLOntology copy = exportManager.createOntology(kept);
            exportManager.saveOntology(copy, new RDFXMLDocumentFormat(),
                    IRI.create(new File(request.path())));
        } catch (Exception error) {
            ctx.status(500).json(Map.of("error", "Export failed: " + error.getMessage()));
            return;
        }
        ctx.json(Map.of(
                "path", new File(request.path()).getAbsolutePath(),
                "axioms", kept.size(),
                "skipped", skipped,
                "includeRestricted", includeRestricted));
    }

    private record Plan(String name, List<OWLOntologyChange> changes, List<String> summary,
                        List<String> entities, Map<String, Object> params) {
    }

    /**
     * Structured axioms use the same preview/commit path as the string actions.
     * Class expressions are JSON objects, not Manchester text.
     *
     * <p>{@code add-subclass-expression} and {@code add-equivalent-class}:
     * {@code iri} is the selected class. {@code expression} is one of
     * {@code {"kind":"class","iri"}},
     * {@code {"kind":"some"|"only","property","filler"}},
     * {@code {"kind":"min"|"max"|"exactly","property","cardinality","filler"?}},
     * {@code {"kind":"intersection"|"union","operands"}},
     * {@code {"kind":"complement","operand"}}.
     * A data-property filler is {@code {"kind":"datatype","iri"}}.
     *
     * <p>{@code add-property-characteristic}: {@code iri} plus
     * {@code characteristic} of functional, inverse-functional, transitive,
     * symmetric, asymmetric, reflexive, irreflexive, inverse ({@code inverse}
     * IRI), or chain ({@code chain} array of at least two property IRIs).
     * Functional also accepts a data property.
     *
     * <p>{@code add-object-assertion}: {@code iri}, {@code property}, {@code target}.
     * {@code add-data-assertion}: {@code iri}, {@code property}, {@code value},
     * optional {@code datatype}. {@code add-same-as} and {@code add-different-from}:
     * {@code iri}, {@code other}. Class assertions stay on {@code add-type}
     * ({@code iri}, {@code class}).
     */
    private Plan plan(Main.ActionRequest request) {
        String name = request.action() == null ? "" : request.action();
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        OWLEntity entity = entity(text(params, "iri"));
        List<OWLOntologyChange> changes = new ArrayList<>();
        List<String> summary = new ArrayList<>();

        Plugin.PluginAction pluginAction = plugins.stream()
                .flatMap(plugin -> plugin.actions().stream())
                .filter(action -> action.name().equals(name))
                .findFirst()
                .orElse(null);
        if (pluginAction != null) {
            int axiomCountBefore = ontology.getAxiomCount();
            List<Plugin.PluginChange> planned = pluginAction.plan(this, stringParams(params));
            if (ontology.getAxiomCount() != axiomCountBefore) {
                throw new IllegalStateException(
                        "Plugin " + pluginAction.name() + " mutated the ontology while planning");
            }
            for (Plugin.PluginChange change : planned) {
                changes.add(change.add()
                        ? new AddAxiom(ontology, change.axiom())
                        : new RemoveAxiom(ontology, change.axiom()));
                String rendered = renderer.render(change.axiom());
                summary.add((change.add() ? "add: " : "remove: ")
                        + (rendered == null || rendered.isBlank() ? change.axiom() : rendered));
            }
            return new Plan(name, changes, summary,
                    entity == null ? List.of() : List.of(entity.getIRI().toString()), params);
        }

        switch (name) {
            case "set-label" -> {
                require(entity, "iri");
                String label = requiredText(params, "label");
                List<OWLAnnotation> existing = EntitySearcher
                        .getAnnotations(entity, ontology, dataFactory.getRDFSLabel()).toList();
                for (OWLAnnotation annotation : existing) {
                    changes.add(new RemoveAxiom(ontology, dataFactory.getOWLAnnotationAssertionAxiom(
                            annotation.getProperty(), entity.getIRI(), annotation.getValue())));
                    summary.add("remove label: " + annotation.getValue());
                }
                changes.add(new AddAxiom(ontology, dataFactory.getOWLAnnotationAssertionAxiom(
                        dataFactory.getRDFSLabel(), entity.getIRI(), dataFactory.getOWLLiteral(label))));
                summary.add("add label: " + label);
            }
            case "add-subclass" -> {
                require(entity, "iri");
                OWLEntity parent = entity(requiredText(params, "parent"));
                require(parent, "parent");
                OWLClass child = entity.asOWLClass();
                OWLClass sup = parent.asOWLClass();
                changes.add(new AddAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(child, sup)));
                summary.add("add: " + label(child) + " sub " + label(sup));
            }
            case "remove-subclass" -> {
                require(entity, "iri");
                OWLEntity parent = entity(requiredText(params, "parent"));
                require(parent, "parent");
                OWLClass child = entity.asOWLClass();
                OWLClass sup = parent.asOWLClass();
                changes.add(new RemoveAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(child, sup)));
                summary.add("remove: " + label(child) + " sub " + label(sup));
            }
            case "set-domain" -> {
                require(entity, "iri");
                OWLEntity domain = entity(requiredText(params, "class"));
                require(domain, "class");
                OWLClassExpression domainExpr = domain.asOWLClass();
                if (entity instanceof OWLObjectProperty property) {
                    EntitySearcher.getDomains(property, ontology).toList()
                            .forEach(existing -> changes.add(new RemoveAxiom(ontology,
                                    dataFactory.getOWLObjectPropertyDomainAxiom(property, existing))));
                    changes.add(new AddAxiom(ontology,
                            dataFactory.getOWLObjectPropertyDomainAxiom(property, domainExpr)));
                } else if (entity instanceof OWLDataProperty property) {
                    EntitySearcher.getDomains(property, ontology).toList()
                            .forEach(existing -> changes.add(new RemoveAxiom(ontology,
                                    dataFactory.getOWLDataPropertyDomainAxiom(property, existing))));
                    changes.add(new AddAxiom(ontology,
                            dataFactory.getOWLDataPropertyDomainAxiom(property, domainExpr)));
                } else {
                    throw new IllegalArgumentException("Domains apply to properties only");
                }
                summary.add("set domain: " + label(domain));
            }
            case "set-range" -> {
                require(entity, "iri");
                if (entity instanceof OWLObjectProperty property) {
                    OWLEntity range = entity(requiredText(params, "class"));
                    require(range, "class");
                    EntitySearcher.getRanges(property, ontology).toList()
                            .forEach(existing -> changes.add(new RemoveAxiom(ontology,
                                    dataFactory.getOWLObjectPropertyRangeAxiom(property, existing))));
                    changes.add(new AddAxiom(ontology,
                            dataFactory.getOWLObjectPropertyRangeAxiom(property, range.asOWLClass())));
                    summary.add("set range: " + label(range));
                } else if (entity instanceof OWLDataProperty property) {
                    String datatype = requiredText(params, "datatype");
                    OWLDatatype range = dataFactory.getOWLDatatype(IRI.create(datatypeIri(datatype)));
                    EntitySearcher.getRanges(property, ontology).toList()
                            .forEach(existing -> changes.add(new RemoveAxiom(ontology,
                                    dataFactory.getOWLDataPropertyRangeAxiom(property, existing))));
                    changes.add(new AddAxiom(ontology,
                            dataFactory.getOWLDataPropertyRangeAxiom(property, range)));
                    summary.add("set range: " + datatype);
                } else {
                    throw new IllegalArgumentException("Ranges apply to properties only");
                }
            }
            case "add-type" -> {
                require(entity, "iri");
                OWLEntity type = entity(requiredText(params, "class"));
                require(type, "class");
                changes.add(new AddAxiom(ontology,
                        dataFactory.getOWLClassAssertionAxiom(type.asOWLClass(), entity.asOWLNamedIndividual())));
                summary.add("add type: " + label(type));
            }
            case "remove-type" -> {
                require(entity, "iri");
                OWLEntity type = entity(requiredText(params, "class"));
                require(type, "class");
                changes.add(new RemoveAxiom(ontology,
                        dataFactory.getOWLClassAssertionAxiom(type.asOWLClass(), entity.asOWLNamedIndividual())));
                summary.add("remove type: " + label(type));
            }
            case "add-subclass-expression" -> planClassAxiom(entity, params, false, changes, summary);
            case "add-equivalent-class" -> planClassAxiom(entity, params, true, changes, summary);
            case "add-property-characteristic" ->
                    addAxiom(changes, summary, propertyCharacteristic(entity, params));
            case "add-object-assertion" -> planObjectAssertion(entity, params, changes, summary);
            case "add-data-assertion" -> planDataAssertion(entity, params, changes, summary);
            case "add-same-as" -> planIndividualPair(entity, params, true, changes, summary);
            case "add-different-from" -> planIndividualPair(entity, params, false, changes, summary);
            default -> throw new IllegalArgumentException("Unknown action: " + name);
        }

        return new Plan(name, changes, summary, entitiesFor(name, entity, changes), params);
    }

    private static Map<String, String> stringParams(Map<String, Object> params) {
        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("Plugin actions accept string params only");
            }
            strings.put(entry.getKey(), text);
        }
        return strings;
    }

    private static String text(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(key + " must be a string");
    }

    private static String requiredText(Map<String, Object> params, String key) {
        String value = text(params, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static List<String> entitiesFor(String name, OWLEntity subject, List<OWLOntologyChange> changes) {
        boolean structured = switch (name) {
            case "add-subclass-expression", "add-equivalent-class", "add-property-characteristic",
                    "add-object-assertion", "add-data-assertion", "add-same-as", "add-different-from" -> true;
            default -> false;
        };
        if (!structured) {
            return subject == null ? List.of() : List.of(subject.getIRI().toString());
        }
        List<String> iris = new ArrayList<>();
        for (OWLOntologyChange change : changes) {
            change.getAxiom().signature().forEach(signed -> {
                String iri = signed.getIRI().toString();
                if (!iris.contains(iri)) {
                    iris.add(iri);
                }
            });
        }
        if (iris.isEmpty() && subject != null) {
            iris.add(subject.getIRI().toString());
        }
        return iris;
    }

    private void addAxiom(List<OWLOntologyChange> changes, List<String> summary, OWLAxiom axiom) {
        changes.add(new AddAxiom(ontology, axiom));
        summary.add("add: " + renderAxiom(axiom));
    }

    private void planClassAxiom(OWLEntity selected, Map<String, Object> params, boolean equivalent,
                                List<OWLOntologyChange> changes, List<String> summary) {
        require(selected, "iri");
        if (!(selected instanceof OWLClass cls)) {
            throw new IllegalArgumentException("Class axioms apply to classes only");
        }
        if (!params.containsKey("expression")) {
            throw new IllegalArgumentException("Missing expression");
        }
        OWLClassExpression expression = classExpression(params.get("expression"), "expression");
        OWLAxiom axiom = equivalent
                ? dataFactory.getOWLEquivalentClassesAxiom(cls, expression)
                : dataFactory.getOWLSubClassOfAxiom(cls, expression);
        addAxiom(changes, summary, axiom);
    }

    private OWLAxiom propertyCharacteristic(OWLEntity selected, Map<String, Object> params) {
        require(selected, "iri");
        String characteristic = requiredText(params, "characteristic");
        return switch (characteristic) {
            case "functional" -> functional(selected);
            case "inverse-functional" ->
                    dataFactory.getOWLInverseFunctionalObjectPropertyAxiom(objectProperty(selected));
            case "transitive" -> dataFactory.getOWLTransitiveObjectPropertyAxiom(objectProperty(selected));
            case "symmetric" -> dataFactory.getOWLSymmetricObjectPropertyAxiom(objectProperty(selected));
            case "asymmetric" -> dataFactory.getOWLAsymmetricObjectPropertyAxiom(objectProperty(selected));
            case "reflexive" -> dataFactory.getOWLReflexiveObjectPropertyAxiom(objectProperty(selected));
            case "irreflexive" -> dataFactory.getOWLIrreflexiveObjectPropertyAxiom(objectProperty(selected));
            case "inverse" -> inverse(selected, params);
            case "chain" -> chain(selected, params);
            default -> throw new IllegalArgumentException("Unknown property characteristic: " + characteristic);
        };
    }

    private OWLAxiom functional(OWLEntity selected) {
        if (selected instanceof OWLObjectProperty property) {
            return dataFactory.getOWLFunctionalObjectPropertyAxiom(property);
        }
        if (selected instanceof OWLDataProperty property) {
            return dataFactory.getOWLFunctionalDataPropertyAxiom(property);
        }
        throw new IllegalArgumentException("Functional applies to properties only");
    }

    private static OWLObjectProperty objectProperty(OWLEntity entity) {
        if (entity instanceof OWLObjectProperty property) {
            return property;
        }
        throw new IllegalArgumentException("This characteristic applies to object properties only");
    }

    private OWLAxiom inverse(OWLEntity selected, Map<String, Object> params) {
        OWLObjectProperty property = objectProperty(selected);
        OWLEntity other = known(requiredText(params, "inverse"), "inverse");
        if (!(other instanceof OWLObjectProperty inverseProperty)) {
            throw new IllegalArgumentException("inverse must be an object property");
        }
        return dataFactory.getOWLInverseObjectPropertiesAxiom(property, inverseProperty);
    }

    private OWLAxiom chain(OWLEntity selected, Map<String, Object> params) {
        OWLObjectProperty property = objectProperty(selected);
        Object raw = params.get("chain");
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            throw new IllegalArgumentException("chain must list at least two object properties");
        }
        List<OWLObjectProperty> links = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String iri) || iri.isBlank()) {
                throw new IllegalArgumentException("chain entries must be property IRIs");
            }
            OWLEntity link = known(iri, "chain");
            if (!(link instanceof OWLObjectProperty linkProperty)) {
                throw new IllegalArgumentException("chain entries must be object properties");
            }
            links.add(linkProperty);
        }
        return dataFactory.getOWLSubPropertyChainOfAxiom(links, property);
    }

    private void planObjectAssertion(OWLEntity selected, Map<String, Object> params,
                                     List<OWLOntologyChange> changes, List<String> summary) {
        OWLNamedIndividual subject = namedIndividual(selected, "iri");
        OWLEntity propertyEntity = known(requiredText(params, "property"), "property");
        if (!(propertyEntity instanceof OWLObjectProperty property)) {
            throw new IllegalArgumentException("property must be an object property");
        }
        OWLNamedIndividual target = namedIndividual(known(requiredText(params, "target"), "target"), "target");
        addAxiom(changes, summary, dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, target));
    }

    private void planDataAssertion(OWLEntity selected, Map<String, Object> params,
                                   List<OWLOntologyChange> changes, List<String> summary) {
        OWLNamedIndividual subject = namedIndividual(selected, "iri");
        OWLEntity propertyEntity = known(requiredText(params, "property"), "property");
        if (!(propertyEntity instanceof OWLDataProperty property)) {
            throw new IllegalArgumentException("property must be a data property");
        }
        String value = requiredText(params, "value");
        String datatype = text(params, "datatype");
        OWLLiteral literal = datatype == null || datatype.isBlank()
                ? dataFactory.getOWLLiteral(value)
                : dataFactory.getOWLLiteral(value, dataFactory.getOWLDatatype(IRI.create(datatypeIri(datatype))));
        addAxiom(changes, summary, dataFactory.getOWLDataPropertyAssertionAxiom(property, subject, literal));
    }

    private void planIndividualPair(OWLEntity selected, Map<String, Object> params, boolean same,
                                    List<OWLOntologyChange> changes, List<String> summary) {
        OWLNamedIndividual subject = namedIndividual(selected, "iri");
        OWLNamedIndividual other = namedIndividual(known(requiredText(params, "other"), "other"), "other");
        OWLAxiom axiom = same
                ? dataFactory.getOWLSameIndividualAxiom(subject, other)
                : dataFactory.getOWLDifferentIndividualsAxiom(subject, other);
        addAxiom(changes, summary, axiom);
    }

    private static OWLNamedIndividual namedIndividual(OWLEntity entity, String field) {
        require(entity, field);
        if (entity instanceof OWLNamedIndividual individual) {
            return individual;
        }
        throw new IllegalArgumentException(field + " must be an individual");
    }

    private OWLEntity known(String iri, String field) {
        OWLEntity found = entity(iri);
        if (found == null) {
            throw new IllegalArgumentException("Unknown entity for " + field + ": " + iri);
        }
        return found;
    }

    private OWLClassExpression classExpression(Object node, String field) {
        Map<String, Object> tree = object(node, field);
        String kind = text(tree, "kind");
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Missing expression kind");
        }
        return switch (kind) {
            case "class" -> namedClass(requiredText(tree, "iri"));
            case "some", "only" -> restriction(tree, kind);
            case "min", "max", "exactly" -> cardinalityRestriction(tree, kind);
            case "intersection" -> junction(tree, true);
            case "union" -> junction(tree, false);
            case "complement" -> dataFactory.getOWLObjectComplementOf(
                    classExpression(tree.get("operand"), "operand"));
            default -> throw new IllegalArgumentException("Unknown expression kind: " + kind);
        };
    }

    private OWLClass namedClass(String iri) {
        OWLEntity found = known(iri, "expression");
        if (!(found instanceof OWLClass cls)) {
            throw new IllegalArgumentException("Expected a class: " + iri);
        }
        return cls;
    }

    private OWLEntity propertyOf(Map<String, Object> tree) {
        return known(requiredText(tree, "property"), "property");
    }

    private OWLClassExpression restriction(Map<String, Object> tree, String kind) {
        OWLEntity property = propertyOf(tree);
        if (!tree.containsKey("filler") || tree.get("filler") == null) {
            throw new IllegalArgumentException("Missing filler");
        }
        if (property instanceof OWLObjectProperty objectProperty) {
            OWLClassExpression filler = classExpression(tree.get("filler"), "filler");
            if ("some".equals(kind)) {
                return dataFactory.getOWLObjectSomeValuesFrom(objectProperty, filler);
            }
            if ("only".equals(kind)) {
                return dataFactory.getOWLObjectAllValuesFrom(objectProperty, filler);
            }
            throw new IllegalArgumentException("Unknown restriction kind: " + kind);
        }
        if (property instanceof OWLDataProperty dataProperty) {
            OWLDataRange filler = dataRange(tree.get("filler"));
            if ("some".equals(kind)) {
                return dataFactory.getOWLDataSomeValuesFrom(dataProperty, filler);
            }
            if ("only".equals(kind)) {
                return dataFactory.getOWLDataAllValuesFrom(dataProperty, filler);
            }
            throw new IllegalArgumentException("Unknown restriction kind: " + kind);
        }
        throw new IllegalArgumentException("Restrictions apply to object or data properties");
    }

    private OWLClassExpression cardinalityRestriction(Map<String, Object> tree, String kind) {
        int cardinality = cardinality(tree.get("cardinality"));
        OWLEntity property = propertyOf(tree);
        boolean qualified = tree.containsKey("filler") && tree.get("filler") != null;
        if (property instanceof OWLObjectProperty objectProperty) {
            if (!qualified) {
                return switch (kind) {
                    case "min" -> dataFactory.getOWLObjectMinCardinality(cardinality, objectProperty);
                    case "max" -> dataFactory.getOWLObjectMaxCardinality(cardinality, objectProperty);
                    case "exactly" -> dataFactory.getOWLObjectExactCardinality(cardinality, objectProperty);
                    default -> throw new IllegalArgumentException("Unknown cardinality kind: " + kind);
                };
            }
            OWLClassExpression filler = classExpression(tree.get("filler"), "filler");
            return switch (kind) {
                case "min" -> dataFactory.getOWLObjectMinCardinality(cardinality, objectProperty, filler);
                case "max" -> dataFactory.getOWLObjectMaxCardinality(cardinality, objectProperty, filler);
                case "exactly" -> dataFactory.getOWLObjectExactCardinality(cardinality, objectProperty, filler);
                default -> throw new IllegalArgumentException("Unknown cardinality kind: " + kind);
            };
        }
        if (property instanceof OWLDataProperty dataProperty) {
            if (!qualified) {
                return switch (kind) {
                    case "min" -> dataFactory.getOWLDataMinCardinality(cardinality, dataProperty);
                    case "max" -> dataFactory.getOWLDataMaxCardinality(cardinality, dataProperty);
                    case "exactly" -> dataFactory.getOWLDataExactCardinality(cardinality, dataProperty);
                    default -> throw new IllegalArgumentException("Unknown cardinality kind: " + kind);
                };
            }
            OWLDataRange filler = dataRange(tree.get("filler"));
            return switch (kind) {
                case "min" -> dataFactory.getOWLDataMinCardinality(cardinality, dataProperty, filler);
                case "max" -> dataFactory.getOWLDataMaxCardinality(cardinality, dataProperty, filler);
                case "exactly" -> dataFactory.getOWLDataExactCardinality(cardinality, dataProperty, filler);
                default -> throw new IllegalArgumentException("Unknown cardinality kind: " + kind);
            };
        }
        throw new IllegalArgumentException("Restrictions apply to object or data properties");
    }

    private OWLClassExpression junction(Map<String, Object> tree, boolean intersection) {
        Object raw = tree.get("operands");
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            throw new IllegalArgumentException("operands must list at least two expressions");
        }
        LinkedHashSet<OWLClassExpression> operands = new LinkedHashSet<>();
        for (Object item : list) {
            operands.add(classExpression(item, "operands"));
        }
        if (operands.size() < 2) {
            throw new IllegalArgumentException("operands must list at least two expressions");
        }
        return intersection
                ? dataFactory.getOWLObjectIntersectionOf(operands)
                : dataFactory.getOWLObjectUnionOf(operands);
    }

    private OWLDataRange dataRange(Object node) {
        Map<String, Object> tree = object(node, "filler");
        String kind = text(tree, "kind");
        if (!"datatype".equals(kind)) {
            throw new IllegalArgumentException("Data property filler must be a datatype");
        }
        String iri = text(tree, "iri");
        if (iri == null || iri.isBlank()) {
            iri = text(tree, "name");
        }
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("Missing datatype");
        }
        return dataFactory.getOWLDatatype(IRI.create(datatypeIri(iri)));
    }

    private static int cardinality(Object value) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            if (parsed < 0 || parsed != number.doubleValue()) {
                throw new IllegalArgumentException("Cardinality must be a non-negative integer");
            }
            return parsed;
        }
        if (value instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.trim());
                if (parsed < 0) {
                    throw new IllegalArgumentException("Cardinality must be a non-negative integer");
                }
                return parsed;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Cardinality must be a non-negative integer");
            }
        }
        throw new IllegalArgumentException("Missing cardinality");
    }

    private static Map<String, Object> object(Object node, String field) {
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " has a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String datatypeIri(String name) {
        String trimmed = name.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("urn:")) {
            return trimmed;
        }
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":", 2);
            return "http://www.w3.org/2001/XMLSchema#" + parts[1];
        }
        return "http://www.w3.org/2001/XMLSchema#" + trimmed;
    }

    @Override
    public OWLDataFactory dataFactory() {
        return dataFactory;
    }

    @Override
    public synchronized OWLEntity entity(String iri) {
        if (ontology == null || iri == null || iri.isBlank()) {
            return null;
        }
        return ontology.signature()
                .filter(candidate -> candidate.getIRI().toString().equals(iri))
                .findFirst()
                .orElse(null);
    }

    private static IRI parseIri(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        try {
            URI uri = URI.create(raw.trim());
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                throw new IllegalArgumentException("Invalid IRI for " + field + ": " + raw);
            }
            return IRI.create(uri);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid IRI for " + field + ": " + raw);
        }
    }

    private static void require(OWLEntity entity, String param) {
        if (entity == null) {
            throw new IllegalArgumentException("Unknown entity for " + param);
        }
    }

    private static String describe(OWLOntologyChange change) {
        String kind = change instanceof AddAxiom ? "+" : "-";
        return kind + " " + change.getAxiom();
    }

    /** Evidence rows for one entity, newest first. */
    public synchronized void evidence(String iri, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (iri == null || iri.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing iri"));
            return;
        }
        List<Map<String, Object>> rows = evidence == null ? List.of() : evidence.forEntity(iri);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("import", evidence == null ? null : evidence.lastImport());
        ctx.json(response);
    }

    /**
     * Explains one axiom: whether it is asserted or inferred, and the evidence
     * or supporting assertions behind it.
     */
    public synchronized void why(String iri, String axiomText, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        if (iri == null || iri.isBlank() || axiomText == null || axiomText.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing iri or axiom"));
            return;
        }
        OWLEntity entity = ontology.signature()
                .filter(candidate -> candidate.getIRI().toString().equals(iri))
                .findFirst()
                .orElse(null);
        if (entity == null) {
            ctx.status(404).json(Map.of("error", "Entity not found: " + iri));
            return;
        }
        OWLAxiom match = ontology.getReferencingAxioms(entity).stream()
                .filter(axiom -> renderer.render(axiom).equals(axiomText))
                .findFirst()
                .orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("axiom", axiomText);
        if (match != null && ontology.containsAxiom(match)) {
            response.put("status", "asserted");
            response.put("evidence", evidence == null ? List.of() : evidence.forAxiom(axiomText));
        } else {
            response.put("status", "inferred");
            response.put("evidence", List.of());
            response.put("supporting", ontology.getReferencingAxioms(entity).stream()
                    .filter(axiom -> !axiom.isAnnotationAxiom())
                    .map(renderer::render)
                    .filter(rendered -> !rendered.isBlank() && !rendered.equals(axiomText))
                    .sorted()
                    .limit(15)
                    .toList());
            response.put("note", reasoner == null
                    ? "Not asserted. Start the reasoner to confirm entailment."
                    : "Entailed by the structural reasoner from the supporting assertions.");
        }
        ctx.json(response);
    }

    private static List<OWLOntologyChange> inverse(List<OWLOntologyChange> changes) {
        List<OWLOntologyChange> result = new ArrayList<>();
        for (OWLOntologyChange change : changes) {
            if (change instanceof AddAxiom add) {
                result.add(new RemoveAxiom(add.getOntology(), add.getAxiom()));
            } else if (change instanceof RemoveAxiom remove) {
                result.add(new AddAxiom(remove.getOntology(), remove.getAxiom()));
            }
        }
        return result;
    }

    private Map<String, Object> state() {
        return Map.of(
                "dirty", undoStack.size() != savedDepth,
                "canUndo", !undoStack.isEmpty(),
                "canRedo", !redoStack.isEmpty());
    }

    private boolean requireOntology(Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return false;
        }
        return true;
    }

    private List<Map<String, String>> relatedClasses(OWLClass cls) {
        return namedClasses(EntitySearcher.getSuperClasses(cls, ontology));
    }

    private List<Map<String, String>> namedClasses(Stream<OWLClassExpression> expressions) {
        return expressions
                .filter(OWLClassExpression::isNamed)
                .map(OWLClassExpression::asOWLClass)
                .distinct()
                .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", label(cls)))
                .toList();
    }

    private List<String> annotationsOf(OWLEntity entity) {
        return EntitySearcher.getAnnotations(entity, ontology)
                .map(this::renderAnnotation)
                .distinct()
                .limit(20)
                .toList();
    }

    @Override
    public synchronized List<OWLEntity> entities() {
        if (ontology == null) {
            return List.of();
        }
        return ontology.signature().toList();
    }

    @Override
    public synchronized List<String> axiomsOf(OWLEntity entity) {
        if (ontology == null || entity == null) {
            return List.of();
        }
        return ontology.getReferencingAxioms(entity).stream()
                .filter(axiom -> !axiom.isAnnotationAxiom())
                .filter(axiom -> !axiom.isOfType(AxiomType.DECLARATION))
                .map(renderer::render)
                .filter(rendered -> !rendered.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private boolean isTopLevel(OWLClass cls) {
        // Classes whose only named parent is owl:Thing are shown at the root so
        // the tree does not start with a single "Thing" node.
        return EntitySearcher.getSuperClasses(cls, ontology)
                .filter(OWLClassExpression::isNamed)
                .map(OWLClassExpression::asOWLClass)
                .allMatch(OWLClass::isOWLThing);
    }

    private boolean hasChildren(OWLClass cls) {
        return EntitySearcher.getSubClasses(cls, ontology).findAny().isPresent();
    }

    @Override
    public synchronized String label(OWLEntity entity) {
        if (entity == null) {
            return "";
        }
        if (ontology == null) {
            return entity.getIRI().getShortForm();
        }
        return EntitySearcher.getAnnotations(entity, ontology, dataFactory.getRDFSLabel())
                .findFirst()
                .map(annotation -> annotation.getValue().asLiteral().map(literal -> literal.getLiteral()).orElse(null))
                .orElseGet(() -> entity.getIRI().getShortForm());
    }

    private String renderAnnotation(OWLAnnotation annotation) {
        String property = annotation.getProperty().getIRI().getShortForm();
        String value = annotation.getValue().asLiteral()
                .map(literal -> literal.getLiteral())
                .orElseGet(() -> annotation.getValue().toString());
        return property + ": " + value;
    }

    private static String kind(OWLEntity entity) {
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

    /**
     * Resolves existing short forms first and creates a new entity when the
     * name is not in the ontology yet. This lets a batch of Manchester frames
     * build an ontology from scratch.
     */
    private static final class AutoEntityChecker implements OWLEntityChecker {

        private final OWLEntityChecker delegate;
        private final OWLOntology ontology;
        private final OWLDataFactory dataFactory;

        AutoEntityChecker(OWLEntityChecker delegate, OWLOntology ontology, OWLDataFactory dataFactory) {
            this.delegate = delegate;
            this.ontology = ontology;
            this.dataFactory = dataFactory;
        }

        private IRI iri(String name) {
            if (name.startsWith("<") && name.endsWith(">")) {
                return IRI.create(name.substring(1, name.length() - 1));
            }
            if (name.startsWith("http://") || name.startsWith("https://")) {
                return IRI.create(name);
            }
            String base = ontology.getOntologyID().getOntologyIRI()
                    .map(IRI::toString)
                    .orElse("http://onto.studio/generated");
            return IRI.create(base + "#" + name.replaceAll("\\s+", "_"));
        }

        @Override
        public OWLClass getOWLClass(String name) {
            if ("Thing".equals(name) || "owl:Thing".equals(name)) {
                return dataFactory.getOWLThing();
            }
            if ("Nothing".equals(name) || "owl:Nothing".equals(name)) {
                return dataFactory.getOWLNothing();
            }
            OWLClass found = delegate.getOWLClass(name);
            return found != null ? found : dataFactory.getOWLClass(iri(name));
        }

        @Override
        public OWLObjectProperty getOWLObjectProperty(String name) {
            if ("topObjectProperty".equals(name) || "owl:topObjectProperty".equals(name)) {
                return dataFactory.getOWLTopObjectProperty();
            }
            if ("bottomObjectProperty".equals(name) || "owl:bottomObjectProperty".equals(name)) {
                return dataFactory.getOWLBottomObjectProperty();
            }
            OWLObjectProperty found = delegate.getOWLObjectProperty(name);
            return found != null ? found : dataFactory.getOWLObjectProperty(iri(name));
        }

        @Override
        public OWLDataProperty getOWLDataProperty(String name) {
            if ("topDataProperty".equals(name) || "owl:topDataProperty".equals(name)) {
                return dataFactory.getOWLTopDataProperty();
            }
            if ("bottomDataProperty".equals(name) || "owl:bottomDataProperty".equals(name)) {
                return dataFactory.getOWLBottomDataProperty();
            }
            OWLDataProperty found = delegate.getOWLDataProperty(name);
            return found != null ? found : dataFactory.getOWLDataProperty(iri(name));
        }

        @Override
        public OWLAnnotationProperty getOWLAnnotationProperty(String name) {
            OWLAnnotationProperty found = delegate.getOWLAnnotationProperty(name);
            if (found != null) {
                return found;
            }
            OWLAnnotationProperty builtIn = builtInAnnotationProperty(name);
            return builtIn != null ? builtIn : dataFactory.getOWLAnnotationProperty(iri(name));
        }

        /** Standard annotation properties must never become local entities. */
        private OWLAnnotationProperty builtInAnnotationProperty(String name) {
            return switch (name) {
                case "rdfs:label", "label" -> dataFactory.getRDFSLabel();
                case "rdfs:comment", "comment" -> dataFactory.getRDFSComment();
                case "rdfs:seeAlso", "seeAlso" -> dataFactory.getRDFSSeeAlso();
                case "rdfs:isDefinedBy", "isDefinedBy" -> dataFactory.getRDFSIsDefinedBy();
                case "owl:deprecated", "deprecated" -> dataFactory.getOWLDeprecated();
                case "owl:versionInfo", "versionInfo" -> dataFactory.getOWLVersionInfo();
                default -> null;
            };
        }

        @Override
        public OWLDatatype getOWLDatatype(String name) {
            OWLDatatype found = delegate.getOWLDatatype(name);
            if (found != null) {
                return found;
            }
            String local = name.startsWith("xsd:") ? name.substring(4) : name;
            if (java.util.Set.of("string", "integer", "decimal", "boolean", "double", "float",
                    "dateTime", "date", "time", "anyURI", "nonNegativeInteger")
                    .contains(local)) {
                return dataFactory.getOWLDatatype(
                        IRI.create("http://www.w3.org/2001/XMLSchema#" + local));
            }
            return dataFactory.getOWLDatatype(iri(name));
        }

        @Override
        public OWLNamedIndividual getOWLIndividual(String name) {
            OWLNamedIndividual found = delegate.getOWLIndividual(name);
            return found != null ? found : dataFactory.getOWLNamedIndividual(iri(name));
        }
    }
}
