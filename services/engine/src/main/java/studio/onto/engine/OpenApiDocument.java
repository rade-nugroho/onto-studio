package studio.onto.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI document for the routes an ontology-aware app calls. Credential
 * values are not part of this document.
 */
final class OpenApiDocument {

    private OpenApiDocument() {
    }

    static Map<String, Object> document() {
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        fieldMap.put("title", "http://example.org#title");
        Map<String, Object> previewExample = new LinkedHashMap<>();
        previewExample.put("connectionName", "default");
        previewExample.put("service", "github");
        previewExample.put("actionId", "github.list_issues");
        previewExample.put("classIri", "http://example.org#Issue");
        previewExample.put("idField", "id");
        previewExample.put("fieldMap", fieldMap);
        previewExample.put("sampleLimit", 2);

        Map<String, Object> mappingSchema = obj(
                "type", "object",
                "required", List.of("connectionName", "service", "actionId", "idField", "fieldMap"),
                "properties", obj(
                        "connectionName", obj("type", "string", "description",
                                "Connection alias. Gateway field alias; the engine returns it as name."),
                        "service", obj("type", "string", "description", "Provider id, for example github."),
                        "actionId", obj("type", "string", "description", "Read action id, for example github.list_issues."),
                        "classIri", obj("type", "string"),
                        "newClassName", obj("type", "string", "description",
                                "Used when classIri is omitted. Commit may declare this class. Individuals are not added to the OWL file."),
                        "idField", obj("type", "string", "description", "Connector field used as the individual id and label."),
                        "fieldMap", obj(
                                "type", "object",
                                "additionalProperties", obj("type", "string"),
                                "description", "Maps a connector output field name to a property IRI."),
                        "actionInput", obj("type", "object", "description", "Input object sent to the read action."),
                        "sampleLimit", obj("type", "integer", "minimum", 1, "maximum", 50)));

        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("/health", obj(
                "get", obj(
                        "operationId", "health",
                        "summary", "Engine health",
                        "description", "When KEYCLOAK_ISSUER is unset this route still requires X-Engine-Token. When the issuer is set, GET /health is anonymous.",
                        "responses", jsonResponse("status and version"))));
        paths.put("/openapi.json", obj(
                "get", obj(
                        "operationId", "openapi",
                        "summary", "This OpenAPI document",
                        "description", "When KEYCLOAK_ISSUER is set, GET /openapi.json is anonymous. When it is unset, X-Engine-Token is required. This document does not contain secret values.",
                        "responses", jsonResponse("OpenAPI document"))));
        paths.put("/ontology/open", obj(
                "post", obj(
                        "operationId", "openOntology",
                        "summary", "Open an ontology file",
                        "requestBody", jsonBody(obj(
                                "type", "object",
                                "required", List.of("path"),
                                "properties", obj("path", obj("type", "string", "description", "Absolute path to an OWL file.")))),
                        "responses", jsonResponse("iri, path, axiomCount, entityCount, dirty, canUndo, canRedo"))));
        paths.put("/ontology/new", obj(
                "post", obj(
                        "operationId", "newOntology",
                        "summary", "Create an ontology file",
                        "requestBody", jsonBody(obj(
                                "type", "object",
                                "required", List.of("path"),
                                "properties", obj("path", obj("type", "string")))),
                        "responses", jsonResponse("iri, path, axiomCount, entityCount, dirty, canUndo, canRedo"))));
        paths.put("/search", obj(
                "get", obj(
                        "operationId", "search",
                        "summary", "Search classes and mapped individuals",
                        "description", "Mapped individuals are read live from the connector. They are not stored in the OWL file. label is the idField value. kind is individual.",
                        "parameters", List.of(
                                query("q", "Case-insensitive match against label or IRI."),
                                query("kind", "Optional entity kind filter, for example individual or class.")),
                        "responses", jsonResponse("results: iri, label, kind"))));
        paths.put("/entity", obj(
                "get", obj(
                        "operationId", "entity",
                        "summary", "Describe one entity or mapped individual",
                        "description", "A mapped individual includes types, axioms, and connectorSource (service, actionId, connectionName). The individual is not written into the OWL file.",
                        "parameters", List.of(query("iri", "Entity or individual IRI.")),
                        "responses", jsonResponse("Entity or mapped individual"))));
        paths.put("/hierarchy", obj(
                "get", obj(
                        "operationId", "hierarchy",
                        "summary", "Class hierarchy slice",
                        "parameters", List.of(
                                query("parent", "Parent class IRI. Omit for the top level."),
                                query("inferred", "When true, use the reasoner.")),
                        "responses", jsonResponse("parent, inferred, children"))));
        paths.put("/connectors/apps", obj(
                "get", obj(
                        "operationId", "connectorApps",
                        "summary", "Provider catalog",
                        "parameters", List.of(
                                query("q", "Filter by provider id or name."),
                                query("limit", "Page size.")),
                        "responses", jsonResponse("apps (id, name, authType, locallyExecutable), truncated, gatewayAvailable"))));
        paths.put("/connectors/connections", obj(
                "get", obj(
                        "operationId", "connectorConnections",
                        "summary", "Gateway connections",
                        "responses", jsonResponse("connections (service, name, authType, status, appId), gatewayAvailable"))));
        paths.put("/connectors/actions", obj(
                "get", obj(
                        "operationId", "connectorActions",
                        "summary", "Read actions for one app",
                        "description", "Write and destructive actions are omitted. Each action has id, description, and outputFields.",
                        "parameters", List.of(query("app", "Provider id.")),
                        "responses", jsonResponse("actions, gatewayAvailable"))));
        paths.put("/connectors/mapping/preview", obj(
                "post", obj(
                        "operationId", "previewConnectorMapping",
                        "summary", "Preview a connector mapping",
                        "description", "Runs the live read action and returns sample individuals. stored is false. mapping.json is not written and rows are not added to the OWL file.",
                        "requestBody", obj(
                                "required", true,
                                "content", obj(
                                        "application/json", obj(
                                                "schema", obj("$ref", "#/components/schemas/ConnectorMappingRequest"),
                                                "example", previewExample))),
                        "responses", jsonResponse("classIri, individuals, stored"))));
        paths.put("/connectors/mapping/commit", obj(
                "post", obj(
                        "operationId", "commitConnectorMapping",
                        "summary", "Save a connector mapping",
                        "description", "Writes the mapping to .onto-studio/mapping.json and records evidence without secret values. Individuals stay out of the OWL file. stored is true.",
                        "requestBody", jsonBody(obj("$ref", "#/components/schemas/ConnectorMappingRequest")),
                        "responses", jsonResponse("classIri, mapping, stored"))));
        paths.put("/systemone/submit", obj(
                "post", obj(
                        "operationId", "submitSystemOne",
                        "summary", "Submit a System One request",
                        "description", "Forwards the JSON body to POST https://openrouter.ai/api/v1/systemone. "
                                + "Requires OPENROUTER_API_KEY in the environment. The key is not part of this document. "
                                + "When the key is unset the route returns an error object and does not call OpenRouter.",
                        "requestBody", jsonBody(obj(
                                "type", "object",
                                "required", List.of("model", "state", "questions"),
                                "properties", obj(
                                        "model", obj("type", "string", "description",
                                                "System One model id, for example typesafe/jev-1.13."),
                                        "state", obj(
                                                "description", "Content to evaluate: a plain string, or a JSON object or array.",
                                                "oneOf", List.of(
                                                        obj("type", "string"),
                                                        obj("type", "object"),
                                                        obj("type", "array"))),
                                        "questions", obj("type", "object", "description",
                                                "Questions keyed by name. Each value has type noul, choice, score, or box."),
                                        "assets", obj("type", "object", "description",
                                                "Images keyed by reference string. Each value has format and data_base64."),
                                        "provider", obj("type", "object", "description", "Provider routing preferences."),
                                        "session_id", obj("type", "string", "maxLength", 256),
                                        "trace", obj("type", "object", "description",
                                                "Observability metadata. Known keys include trace_id, trace_name, span_name, generation_name, and parent_span_id."),
                                        "user", obj("type", "string", "maxLength", 256)))),
                        "responses", jsonResponse("model, answers, usage, and optional id, provider, and annotations"))));

        return obj(
                "openapi", "3.0.3",
                "info", obj(
                        "title", "Onto Studio Engine",
                        "version", "0.1.0",
                        "description", "Local ontology workbench API. Send X-Engine-Token when KEYCLOAK_ISSUER is unset. "
                                + "When KEYCLOAK_ISSUER is set, send Authorization: Bearer with an access token Keycloak issued "
                                + "after adaptive authentication. Audience defaults to onto-studio (KEYCLOAK_AUDIENCE). "
                                + "Connector credentials are not listed here."),
                "servers", List.of(obj(
                        "url", "http://127.0.0.1:7717",
                        "description", "Default local engine. The desktop shell prints the bound port.")),
                "security", List.of(
                        obj("engineToken", List.of()),
                        obj("keycloak", List.of())),
                "paths", paths,
                "components", obj(
                        "securitySchemes", obj(
                                "engineToken", obj(
                                        "type", "apiKey",
                                        "in", "header",
                                        "name", "X-Engine-Token",
                                        "description", "Shared secret printed by the engine on startup. Required on every route when KEYCLOAK_ISSUER is unset."),
                                "keycloak", obj(
                                        "type", "http",
                                        "scheme", "bearer",
                                        "bearerFormat", "JWT",
                                        "description", "Access token issued by Keycloak. Required when KEYCLOAK_ISSUER is set, except GET /health and GET /openapi.json. The engine checks issuer, expiry, and audience.")),
                        "schemas", obj("ConnectorMappingRequest", mappingSchema)));
    }

    private static Map<String, Object> query(String name, String description) {
        return obj(
                "name", name,
                "in", "query",
                "required", false,
                "schema", obj("type", "string"),
                "description", description);
    }

    private static Map<String, Object> jsonBody(Map<String, Object> schema) {
        return obj(
                "required", true,
                "content", obj("application/json", obj("schema", schema)));
    }

    private static Map<String, Object> jsonResponse(String description) {
        return obj("200", obj(
                "description", description,
                "content", obj("application/json", obj("schema", obj("type", "object")))));
    }

    private static Map<String, Object> obj(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
