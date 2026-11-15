package studio.onto.engine.connector;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import studio.onto.engine.EvidenceStore;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Engine routes that proxy OpenConnector and persist connector class mappings.
 */
public final class ConnectorService {

    public static final int APP_PAGE_SIZE = 80;

    private final ConnectorClient client = new ConnectorClient();
    private ConnectorMapping mappings;
    private EvidenceStore evidence;
    private OWLOntology ontology;
    private OWLDataFactory dataFactory;
    private LabelResolver labels;

    public synchronized void bindSession(OWLOntology ontology, OWLDataFactory dataFactory,
                                         ConnectorMapping mappings, EvidenceStore evidence,
                                         LabelResolver labels) {
        this.ontology = ontology;
        this.dataFactory = dataFactory;
        this.mappings = mappings;
        this.evidence = evidence;
        this.labels = labels;
    }

  public void listApps(Context ctx) {
        String query = ctx.queryParam("q");
        int limit = parseLimit(ctx.queryParam("limit"), APP_PAGE_SIZE);
        try {
            List<Map<String, Object>> apps = client.listProviderCatalog(query, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("apps", apps);
            body.put("truncated", apps.size() >= limit);
            body.put("gatewayAvailable", true);
            ctx.json(body);
        } catch (ConnectorException error) {
            ctx.status(503).json(gatewayError(error));
        }
    }

    public void listMappings(Context ctx) {
        ctx.json(Map.of(
                "mappings", mappings == null ? List.of() : mappings.all(),
                "gatewayAvailable", true));
    }

    public void listConnections(Context ctx) {
        try {
            ctx.json(Map.of(
                    "connections", client.listConnections(),
                    "gatewayAvailable", true));
        } catch (ConnectorException error) {
            ctx.status(503).json(gatewayError(error));
        }
    }

    public void listActions(Context ctx) {
        String app = ctx.queryParam("app");
        if (app == null || app.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing app"));
            return;
        }
        try {
            ctx.json(Map.of("actions", client.listReadActions(app), "gatewayAvailable", true));
        } catch (ConnectorException error) {
            ctx.status(503).json(gatewayError(error));
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
        }
    }

    public void previewMapping(MappingRequest request, Context ctx) {
        if (!requireMappingRequest(request, ctx)) {
            return;
        }
        try {
            String classIri = resolveClassIri(request);
            int limit = request.sampleLimit() == null ? 5 : Math.max(1, Math.min(request.sampleLimit(), 50));
            List<Map<String, Object>> individuals = fetchIndividuals(request, classIri, limit);
            ctx.json(Map.of(
                    "classIri", classIri,
                    "individuals", individuals,
                    "stored", false));
        } catch (ConnectorException error) {
            ctx.status(503).json(gatewayError(error));
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
        }
    }

    public void commitMapping(MappingRequest request, Context ctx) {
        if (!requireMappingRequest(request, ctx)) {
            return;
        }
        if (mappings == null) {
            ctx.status(409).json(Map.of("error", "No ontology is open"));
            return;
        }
        try {
            String classIri = resolveClassIri(request);
            maybeDeclareClass(classIri, request.newClassName());
            ConnectorMapping.ClassMapping stored = new ConnectorMapping.ClassMapping(
                    classIri,
                    request.service(),
                    request.connectionName(),
                    request.actionId(),
                    request.idField(),
                    request.fieldMap(),
                    request.actionInput() == null ? Map.of() : request.actionInput());
            mappings.put(stored);
            if (evidence != null) {
                evidence.record("connector-mapping", "open-connector", "", List.of(classIri), 1.0, Map.of(
                        "actionName", "connector-map",
                        "service", request.service(),
                        "actionId", request.actionId(),
                        "connectionName", request.connectionName(),
                        "fieldMap", request.fieldMap()));
            }
            ctx.json(Map.of(
                    "classIri", classIri,
                    "mapping", stored,
                    "stored", true));
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", error.getMessage()));
        }
    }

    public Optional<ConnectorMapping.ClassMapping> mappingForClass(String classIri) {
        return mappings == null ? Optional.empty() : mappings.byClassIri(classIri);
    }

    public List<Map<String, Object>> searchMappedIndividuals(String classIri, String query, int limit)
            throws ConnectorException {
        ConnectorMapping.ClassMapping mapping = mappings == null
                ? null
                : mappings.byClassIri(classIri).orElse(null);
        if (mapping == null) {
            return List.of();
        }
        List<Map<String, Object>> individuals = fetchIndividuals(mapping, classIri, Math.min(limit, 50));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return individuals;
        }
        return individuals.stream()
                .filter(row -> String.valueOf(row.get("label")).toLowerCase(Locale.ROOT).contains(needle)
                        || String.valueOf(row.get("iri")).toLowerCase(Locale.ROOT).contains(needle))
                .limit(limit)
                .toList();
    }

    public Map<String, Object> entityForMappedIndividual(String individualIri) throws ConnectorException {
        if (mappings == null) {
            return Map.of();
        }
        for (ConnectorMapping.ClassMapping mapping : mappings.all()) {
            List<Map<String, Object>> rows = fetchIndividuals(mapping, mapping.classIri(), 50);
            for (Map<String, Object> row : rows) {
                if (individualIri.equals(row.get("iri"))) {
                    return row;
                }
            }
        }
        return Map.of();
    }

    private boolean requireMappingRequest(MappingRequest request, Context ctx) {
        if (request == null) {
            ctx.status(400).json(Map.of("error", "Missing body"));
            return false;
        }
        if (request.actionId() == null || request.actionId().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing action id"));
            return false;
        }
        if (request.service() == null || request.service().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing service"));
            return false;
        }
        if (request.connectionName() == null || request.connectionName().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing connection name"));
            return false;
        }
        if ((request.classIri() == null || request.classIri().isBlank())
                && (request.newClassName() == null || request.newClassName().isBlank())) {
            ctx.status(400).json(Map.of("error", "Provide classIri or newClassName"));
            return false;
        }
        if (request.fieldMap() == null || request.fieldMap().isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing field map"));
            return false;
        }
        if (request.idField() == null || request.idField().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing id field"));
            return false;
        }
        return true;
    }

    private String resolveClassIri(MappingRequest request) {
        if (request.classIri() != null && !request.classIri().isBlank()) {
            return request.classIri().trim();
        }
        if (ontology == null) {
            throw new IllegalStateException("No ontology is open");
        }
        String iri = ontology.getOntologyID().getOntologyIRI()
                .map(ontologyIri -> ontologyIri.toString().replaceAll("/$", "") + "#" + sanitizeName(request.newClassName()))
                .orElseThrow(() -> new IllegalStateException("Ontology has no IRI"));
        return iri;
    }

    private void maybeDeclareClass(String classIri, String newClassName) {
        if (ontology == null || dataFactory == null || newClassName == null || newClassName.isBlank()) {
            return;
        }
        OWLClass cls = dataFactory.getOWLClass(IRI.create(classIri));
        if (ontology.containsClassInSignature(cls.getIRI())) {
            return;
        }
        ontology.getOWLOntologyManager().applyChange(new AddAxiom(ontology, dataFactory.getOWLDeclarationAxiom(cls)));
    }

    private List<Map<String, Object>> fetchIndividuals(Object request, String classIri, int limit)
            throws ConnectorException {
        ConnectorMapping.ClassMapping mapping;
        if (request instanceof MappingRequest body) {
            mapping = new ConnectorMapping.ClassMapping(
                    classIri,
                    body.service(),
                    body.connectionName(),
                    body.actionId(),
                    body.idField(),
                    body.fieldMap(),
                    body.actionInput() == null ? Map.of() : body.actionInput());
        } else if (request instanceof ConnectorMapping.ClassMapping stored) {
            mapping = stored;
        } else {
            throw new IllegalArgumentException("Unsupported mapping source");
        }
        JsonNode data = client.executeReadAction(
                mapping.actionId(),
                mapping.connectionName(),
                mapping.actionInput());
        List<JsonNode> rows = extractRows(data);
        List<Map<String, Object>> individuals = new ArrayList<>();
        for (JsonNode row : rows) {
            if (individuals.size() >= limit) {
                break;
            }
            individuals.add(toIndividual(mapping, classIri, row));
        }
        return individuals;
    }

    private Map<String, Object> toIndividual(ConnectorMapping.ClassMapping mapping, String classIri, JsonNode row) {
        String id = readField(row, mapping.idField());
        if (id.isBlank()) {
            id = Integer.toString(row.hashCode());
        }
        String individualIri = classIri.replaceAll("#[^#]*$", "") + "#"
                + urlToken(mapping.service() + "-" + id);
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("iri", individualIri);
        entity.put("label", id);
        entity.put("kind", "individual");
        entity.put("marking", "public");
        entity.put("usage", 0);
        entity.put("superClasses", List.of());
        entity.put("subClasses", List.of());
        entity.put("domains", List.of());
        entity.put("ranges", List.of());
        String classLabel = labels == null ? shortForm(classIri) : labels.label(classIri);
        entity.put("types", List.of(Map.of("iri", classIri, "label", classLabel)));
        entity.put("annotations", List.of());
        entity.put("axioms", proposedAxioms(mapping, individualIri, classIri, row));
        entity.put("connectorSource", Map.of(
                "service", mapping.service(),
                "actionId", mapping.actionId(),
                "connectionName", mapping.connectionName()));
        return entity;
    }

    private List<String> proposedAxioms(ConnectorMapping.ClassMapping mapping, String individualIri,
                                          String classIri, JsonNode row) {
        List<String> axioms = new ArrayList<>();
        axioms.add(shortForm(individualIri) + " : " + shortForm(classIri));
        for (Map.Entry<String, String> entry : mapping.fieldMap().entrySet()) {
            String value = readField(row, entry.getKey());
            if (value.isBlank()) {
                continue;
            }
            axioms.add(shortForm(individualIri) + " " + shortForm(entry.getValue()) + " \"" + value + "\"");
        }
        return axioms;
    }

    private static String readField(JsonNode row, String field) {
        if (row == null || field == null || field.isBlank()) {
            return "";
        }
        JsonNode value = row;
        for (String segment : field.split("\\.")) {
            if (!value.isObject()) {
                return "";
            }
            value = value.get(segment);
            if (value == null || value.isMissingNode()) {
                return "";
            }
        }
        if (value.isNull()) {
            return "";
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return value.toString();
    }

    private List<JsonNode> extractRows(JsonNode data) {
        List<JsonNode> rows = new ArrayList<>();
        if (data == null || data.isMissingNode()) {
            return rows;
        }
        if (data.isArray()) {
            data.forEach(rows::add);
            return rows;
        }
        for (String key : List.of("items", "rows", "records", "data", "results", "issues", "values")) {
            JsonNode nested = data.get(key);
            if (nested != null && nested.isArray()) {
                nested.forEach(rows::add);
                return rows;
            }
        }
        if (data.isObject()) {
            Iterator<String> names = data.fieldNames();
            while (names.hasNext()) {
                JsonNode value = data.get(names.next());
                if (value.isArray()) {
                    value.forEach(rows::add);
                    if (!rows.isEmpty()) {
                        return rows;
                    }
                }
            }
            rows.add(data);
        }
        return rows;
    }

    private static Map<String, Object> gatewayError(ConnectorException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getMessage());
        body.put("code", error.code());
        body.put("gatewayAvailable", false);
        return body;
    }

    private static int parseLimit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(1, Math.min(value, APP_PAGE_SIZE));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String sanitizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Missing class name");
        }
        return trimmed.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String urlToken(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String shortForm(String iri) {
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int index = Math.max(hash, slash);
        return index >= 0 ? iri.substring(index + 1) : iri;
    }

    @FunctionalInterface
    public interface LabelResolver {
        String label(String iri);
    }

    public record MappingRequest(
            String connectionName,
            String service,
            String actionId,
            String classIri,
            String newClassName,
            String idField,
            Map<String, String> fieldMap,
            Map<String, Object> actionInput,
            Integer sampleLimit) {
    }
}