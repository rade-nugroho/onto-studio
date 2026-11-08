package studio.onto.engine;

import io.javalin.http.Context;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxEditorParser;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntaxObjectRenderer;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLIndividual;
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

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Holds the active ontology, answers queries, and records edits for undo/redo.
 */
public final class Workspace {

    private static final int SEARCH_LIMIT = 50;

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory dataFactory = manager.getOWLDataFactory();
    private final DLSyntaxObjectRenderer renderer = new DLSyntaxObjectRenderer();
    private final Deque<List<OWLOntologyChange>> undoStack = new ArrayDeque<>();
    private final Deque<List<OWLOntologyChange>> redoStack = new ArrayDeque<>();

    private OWLOntology ontology;
    private OWLReasoner reasoner;
    private File openedFile;
    private int savedDepth;

    public Workspace() {
        renderer.setShortFormProvider(new SimpleShortFormProvider());
    }

    public synchronized void open(Main.OpenRequest request, Context ctx) {
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
        stopReasonerInternal();
        openedFile = file;
        undoStack.clear();
        redoStack.clear();
        savedDepth = 0;
        ctx.json(Map.of(
                "iri", ontology.getOntologyID().getOntologyIRI().map(IRI::toString).orElse(""),
                "path", file.getAbsolutePath(),
                "axiomCount", ontology.getAxiomCount(),
                "entityCount", ontology.getSignature().size(),
                "dirty", false,
                "canUndo", false,
                "canRedo", false));
    }

    public synchronized void hierarchy(String parentIri, boolean inferred, Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        boolean root = parentIri == null || parentIri.isBlank();
        Stream<OWLClass> candidates;
        if (inferred && reasoner != null) {
            OWLClass start = root ? dataFactory.getOWLThing() : dataFactory.getOWLClass(IRI.create(parentIri));
            candidates = reasoner.getSubClasses(start, true).entities()
                    .filter(entity -> !entity.isOWLNothing());
        } else {
            candidates = root
                    ? ontology.classesInSignature().filter(this::isTopLevel)
                    : EntitySearcher.getSubClasses(dataFactory.getOWLClass(IRI.create(parentIri)), ontology)
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
        response.put("superClasses", entity instanceof OWLClass cls ? relatedClasses(cls) : List.of());
        response.put("domains", domains);
        response.put("ranges", ranges);
        response.put("types", entity instanceof OWLIndividual individual
                ? namedClasses(EntitySearcher.getTypes(individual, ontology))
                : List.of());
        response.put("annotations", annotationsOf(entity));
        response.put("axioms", axiomsOf(entity));
        ctx.json(response);
    }

    public synchronized void search(String query, Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        String needle = query == null ? "" : query.trim().toLowerCase();
        if (needle.isEmpty()) {
            ctx.json(Map.of("results", List.of()));
            return;
        }
        Stream<? extends OWLEntity> entities = Stream.of(
                ontology.classesInSignature(),
                ontology.objectPropertiesInSignature(),
                ontology.dataPropertiesInSignature(),
                ontology.annotationPropertiesInSignature(),
                ontology.individualsInSignature()).flatMap(stream -> stream);
        List<Map<String, Object>> results = entities
                .filter(entity -> label(entity).toLowerCase().contains(needle)
                        || entity.getIRI().getShortForm().toLowerCase().contains(needle))
                .limit(SEARCH_LIMIT)
                .map(entity -> Map.<String, Object>of(
                        "iri", entity.getIRI().toString(),
                        "label", label(entity),
                        "kind", kind(entity)))
                .toList();
        ctx.json(Map.of("results", results));
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
            OWLClass start = dataFactory.getOWLClass(IRI.create(rootIri));
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
        OWLClass sub = dataFactory.getOWLClass(IRI.create(request.sub()));
        OWLClass sup = dataFactory.getOWLClass(IRI.create(request.sup()));
        apply(List.of(new AddAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(sub, sup))));
        ctx.json(state());
    }

    public synchronized void removeSubclass(Main.SubclassRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        OWLClass sub = dataFactory.getOWLClass(IRI.create(request.sub()));
        OWLClass sup = dataFactory.getOWLClass(IRI.create(request.sup()));
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
        List<OWLAxiom> axioms;
        try {
            axioms = parseFrames(request.text());
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
        response.put("added", axioms.stream().map(renderer::render).toList());
        ctx.json(response);
    }

    public synchronized void removeAxiomText(Main.AxiomTextRequest request, Context ctx) {
        if (!requireOntology(ctx)) {
            return;
        }
        List<OWLAxiom> axioms;
        try {
            axioms = parseFrames(request.text());
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
        manager.applyChanges(inverse(changes));
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
        manager.applyChanges(changes);
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
        File target = request.path() == null || request.path().isBlank()
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

    private List<OWLAxiom> parseFrames(String text) {
        ManchesterOWLSyntaxEditorParser parser = new ManchesterOWLSyntaxEditorParser(dataFactory, text);
        parser.setDefaultOntology(ontology);
        BidirectionalShortFormProviderAdapter shortForms =
                new BidirectionalShortFormProviderAdapter(manager, Set.of(ontology), new SimpleShortFormProvider());
        parser.setOWLEntityChecker(new ShortFormEntityChecker(shortForms));
        return parser.parseFrames().stream()
                .filter(pair -> pair.getOntology() == null || pair.getOntology().equals(ontology))
                .map(OntologyAxiomPair::getAxiom)
                .distinct()
                .toList();
    }

    private void apply(List<OWLOntologyChange> changes) {
        manager.applyChanges(changes);
        if (reasoner != null) {
            reasoner.flush();
        }
        undoStack.push(List.copyOf(changes));
        redoStack.clear();
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
        List<String> annotations = new ArrayList<>();
        for (OWLAnnotationProperty property : List.of(
                dataFactory.getRDFSLabel(), dataFactory.getRDFSComment())) {
            EntitySearcher.getAnnotations(entity, ontology, property).forEach(annotation ->
                    annotations.add(renderAnnotation(annotation)));
        }
        return annotations;
    }

    private List<String> axiomsOf(OWLEntity entity) {
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
        return EntitySearcher.getSuperClasses(cls, ontology)
                .noneMatch(OWLClassExpression::isNamed);
    }

    private boolean hasChildren(OWLClass cls) {
        return EntitySearcher.getSubClasses(cls, ontology).findAny().isPresent();
    }

    private String label(OWLEntity entity) {
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
}
