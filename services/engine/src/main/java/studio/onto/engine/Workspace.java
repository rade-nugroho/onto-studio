package studio.onto.engine;

import io.javalin.http.Context;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntaxObjectRenderer;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Holds the active ontology and answers queries about it.
 */
public final class Workspace {

    private static final int SEARCH_LIMIT = 50;

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory dataFactory = manager.getOWLDataFactory();
    private final DLSyntaxObjectRenderer renderer = new DLSyntaxObjectRenderer();
    private OWLOntology ontology;

    public Workspace() {
        renderer.setShortFormProvider(new SimpleShortFormProvider());
    }

    public synchronized void open(Main.OpenRequest request, Context ctx) throws Exception {
        File file = new File(request.path());
        if (!file.isFile()) {
            ctx.status(400).json(Map.of("error", "File not found: " + request.path()));
            return;
        }
        ontology = manager.loadOntologyFromOntologyDocument(new FileDocumentSource(file));
        ctx.json(Map.of(
                "iri", ontology.getOntologyID().getOntologyIRI().map(IRI::toString).orElse(""),
                "path", file.getAbsolutePath(),
                "axiomCount", ontology.getAxiomCount(),
                "entityCount", ontology.getSignature().size()));
    }

    public synchronized void hierarchy(String parentIri, Context ctx) {
        if (ontology == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        boolean root = parentIri == null || parentIri.isBlank();
        Stream<OWLClass> candidates = root
                ? ontology.classesInSignature().filter(this::isTopLevel)
                : EntitySearcher.getSubClasses(dataFactory.getOWLClass(IRI.create(parentIri)), ontology)
                        .filter(OWLClassExpression::isNamed)
                        .map(OWLClassExpression::asOWLClass);
        List<Map<String, Object>> children = candidates
                .distinct()
                .sorted(Comparator.comparing(this::label, String.CASE_INSENSITIVE_ORDER))
                .map(child -> Map.<String, Object>of(
                        "iri", child.getIRI().toString(),
                        "label", label(child),
                        "hasChildren", hasChildren(child)))
                .toList();
        ctx.json(Map.of("parent", root ? "root" : parentIri, "children", children));
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
        OWLClass cls = dataFactory.getOWLClass(IRI.create(iri));
        List<String> annotations = new ArrayList<>();
        for (OWLAnnotationProperty property : List.of(
                dataFactory.getRDFSLabel(), dataFactory.getRDFSComment())) {
            EntitySearcher.getAnnotations(cls, ontology, property).forEach(annotation ->
                    annotations.add(renderAnnotation(annotation)));
        }
        List<String> axioms = ontology.getReferencingAxioms(cls).stream()
                .filter(axiom -> !axiom.isAnnotationAxiom())
                .filter(axiom -> !axiom.isOfType(AxiomType.DECLARATION))
                .map(renderer::render)
                .filter(rendered -> !rendered.isBlank())
                .distinct()
                .sorted()
                .toList();
        ctx.json(Map.of(
                "iri", iri,
                "label", label(cls),
                "annotations", annotations,
                "axioms", axioms));
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
    public synchronized void graph(String rootIri, int depth, Context ctx) {
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
            ontology.classesInSignature().filter(this::isTopLevel).limit(30)
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
            EntitySearcher.getSubClasses(cls, ontology)
                    .filter(OWLClassExpression::isNamed)
                    .map(OWLClassExpression::asOWLClass)
                    .distinct()
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
