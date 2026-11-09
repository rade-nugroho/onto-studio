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
    private OWLOntology mainOntology;
    private OWLOntologyManager mainManager;
    private OWLOntologyManager activeManager;
    private final Map<String, Branch> branches = new LinkedHashMap<>();
    private String activeBranch;
    private EvidenceStore evidence;
    private MarkingStore markings;
    private OWLReasoner reasoner;
    private File openedFile;
    private int savedDepth;

    public Workspace() {
        renderer.setShortFormProvider(new SimpleShortFormProvider());
        activeManager = manager;
    }

    private record Branch(String name, OWLOntology ontology, OWLOntologyManager manager, String createdAt) {
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
        mainOntology = ontology;
        mainManager = manager;
        activeManager = manager;
        branches.clear();
        activeBranch = null;
        evidence = new EvidenceStore(EvidenceStore.defaultPath(file.toPath()));
        markings = new MarkingStore(MarkingStore.defaultPath(file.toPath()));
        evidence.record("import", "file", "", List.of(), 0.9, Map.of(
                "file", file.getAbsolutePath(),
                "count", ontology.getAxiomCount()));
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

    public synchronized String currentPath() {
        return openedFile == null ? null : openedFile.getAbsolutePath();
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
        List<Map<String, Object>> results = entities
                .filter(entity -> !hasKind || kind(entity).equals(kindFilter))
                .filter(entity -> needle.isEmpty()
                        || label(entity).toLowerCase().contains(needle)
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
        Plan plan;
        try {
            plan = plan(request);
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
            return;
        }
        apply(plan.changes, "action:" + plan.name);
        evidence.record("action", "action:" + plan.name, "", plan.entities, 1.0, Map.of(
                "actionName", plan.name,
                "reason", request.reason() == null ? "" : request.reason(),
                "changes", plan.summary,
                "params", plan.params));
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
        String name = request.name() == null ? "" : request.name().trim();
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
        String name = request.name() == null ? "" : request.name().trim();
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
        String name = request.name() == null ? "" : request.name().trim();
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
        if (request.iri() == null || request.iri().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing iri"));
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
        if (request.path() == null || request.path().isBlank()) {
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
                        List<String> entities, Map<String, String> params) {
    }

    private Plan plan(Main.ActionRequest request) {
        String name = request.action() == null ? "" : request.action();
        Map<String, String> params = request.params() == null ? Map.of() : request.params();
        OWLEntity entity = entity(params.get("iri"));
        List<OWLOntologyChange> changes = new ArrayList<>();
        List<String> summary = new ArrayList<>();

        switch (name) {
            case "set-label" -> {
                require(entity, "iri");
                String label = params.get("label");
                if (label == null || label.isBlank()) {
                    throw new IllegalArgumentException("Missing label");
                }
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
                OWLEntity parent = entity(params.get("parent"));
                require(parent, "parent");
                OWLClass child = entity.asOWLClass();
                OWLClass sup = parent.asOWLClass();
                changes.add(new AddAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(child, sup)));
                summary.add("add: " + label(child) + " sub " + label(sup));
            }
            case "remove-subclass" -> {
                require(entity, "iri");
                OWLEntity parent = entity(params.get("parent"));
                require(parent, "parent");
                OWLClass child = entity.asOWLClass();
                OWLClass sup = parent.asOWLClass();
                changes.add(new RemoveAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(child, sup)));
                summary.add("remove: " + label(child) + " sub " + label(sup));
            }
            default -> throw new IllegalArgumentException("Unknown action: " + name);
        }

        List<String> entities = entity == null
                ? List.of()
                : List.of(entity.getIRI().toString());
        return new Plan(name, changes, summary, entities, params);
    }

    private OWLEntity entity(String iri) {
        if (iri == null || iri.isBlank()) {
            return null;
        }
        return ontology.signature()
                .filter(candidate -> candidate.getIRI().toString().equals(iri))
                .findFirst()
                .orElse(null);
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
