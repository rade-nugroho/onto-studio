package studio.onto.engine;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Public discovery pages for agents. These routes do not require a token.
 */
final class DeveloperDocs {

    private DeveloperDocs() {
    }

    static void register(Javalin app) {
        app.get("/llms.txt", ctx -> text(ctx, LLMS));
        app.get("/docs/intro.md", ctx -> text(ctx, INTRO));
        app.get("/docs/example.md", ctx -> text(ctx, EXAMPLE));
    }

    static boolean isPublic(Context ctx) {
        if (!"GET".equals(ctx.method().name())) {
            return false;
        }
        String path = ctx.path();
        return "/llms.txt".equals(path)
                || "/docs/intro.md".equals(path)
                || "/docs/example.md".equals(path);
    }

    private static void text(Context ctx, String body) {
        ctx.contentType("text/plain; charset=utf-8");
        ctx.result(body);
    }

    private static final String LLMS = """
            # Onto Studio

            > Local OWL workbench. Connector mappings read live data without copying rows into the OWL file.

            This file, `/docs/intro.md`, and `/docs/example.md` are public. Other engine routes require header `X-Engine-Token`. When `KEYCLOAK_ISSUER` is set, send a Keycloak bearer token instead. Keep secrets in Infisical or the environment, never in `mapping.json`. Write and destructive connector actions are not executable. POST `/systemone/submit` forwards a System One request (`model`, `state`, and `questions`) to OpenRouter and returns its answers.

            ## Docs

            - [Introduction](/docs/intro.md): What Onto Studio is, and the rules an integrator must follow.
            - [OpenAPI](/openapi.json): Engine API reference.
            - [Example](/docs/example.md): Connect a read action, commit a mapping, then call `/search` or `/entity`.
            - [System One](/systemone/submit): Submit state and typed questions.
            """;

    private static final String INTRO = """
            # Onto Studio

            Onto Studio is a local OWL workbench. The desktop shell starts a Java engine on 127.0.0.1. Ontology edits go through that engine, and each edit is recorded in `.onto-studio/evidence.jsonl`.

            A connector mapping names a connection and a read action. `/search` and `/entity` read matching rows from the connector when you ask. Those rows are not copied into the OWL file. `.onto-studio/mapping.json` stores the mapping only.

            ## Operating rules

            - Send header `X-Engine-Token` on engine API requests. When the environment variable `KEYCLOAK_ISSUER` is set, send a Keycloak bearer token instead. Adaptive checks (device posture, risk, how many factors to require) are configured in the Keycloak extension keycloak-adaptive-authn. This engine does not embed that extension. It only accepts the access token Keycloak issues after those checks (issuer, expiry, and audience `KEYCLOAK_AUDIENCE`, default `onto-studio`).
            - Keep secrets in Infisical or the environment. Never write them into `mapping.json`, `evidence.jsonl`, logs, or the OpenAPI document. When `INFISICAL_CLIENT_ID`, `INFISICAL_CLIENT_SECRET`, and `INFISICAL_PROJECT_ID` are all set, `OPEN_CONNECTOR_TOKEN` is read with universal auth: `POST /api/v1/auth/universal-auth/login`, then `GET /api/v4/secrets/OPEN_CONNECTOR_TOKEN`. `INFISICAL_SITE_URL` defaults to `https://app.infisical.com`. `INFISICAL_ENVIRONMENT` defaults to `dev`.
            - Write and destructive connector actions are not executable. `GET /connectors/actions` lists read actions only.

            The API reference is `/openapi.json`. A worked request is `/docs/example.md`. The index is `/llms.txt`.
            """;

    private static final String EXAMPLE = """
            # Example: connect a read action

            Open an ontology before committing a mapping. `POST /ontology/open` with `{ "path" }` set to the ontology file. Preview does not write `.onto-studio/mapping.json`. Commit stores the mapping there and does not copy connector rows into the OWL file.

            The calls below require `X-Engine-Token`. This page does not.

            ## Preview

            `POST /connectors/mapping/preview`

            ```json
            {
              "connectionName": "default",
              "service": "github",
              "actionId": "github.list_issues",
              "classIri": "http://example.org#Issue",
              "idField": "id",
              "fieldMap": { "title": "http://example.org#title" },
              "sampleLimit": 2
            }
            ```

            Required: `connectionName`, `service`, `actionId`, `idField`, `fieldMap`, and either `classIri` or `newClassName`. `fieldMap` keys are output fields of the read action. Values are property IRIs. `actionInput` is optional and is passed through to the read action. `sampleLimit` caps how many rows the preview returns (default 5, maximum 50) and is not stored on the mapping.

            The response fields are `classIri`, `individuals`, and `stored` (`false` for a preview).

            ## Commit

            `POST /connectors/mapping/commit` accepts the same JSON. The response fields are `classIri`, `mapping`, and `stored` (`true`). `mapping` contains `classIri`, `service`, `connectionName`, `actionId`, `idField`, `fieldMap`, and `actionInput`.

            Commit answers 409 `{ "error": "No ontology is open" }` when no ontology is open.

            ## Search and entity

            `GET /search?q=1&kind=individual` returns `{ "results": [ { "iri", "label", "kind" } ] }`. `q` is matched against `label` and `iri`. For this mapping `label` is the `idField` value `1`, and the individual IRI is `http://example.org#github-1`. Individuals loaded through a mapping use `"kind": "individual"`. Optional query parameter: `kind`.

            `GET /entity?iri=` with an individual IRI from that search returns the live individual: `iri`, `label`, `kind`, `types`, `axioms`, and `connectorSource` (`service`, `actionId`, `connectionName`). When that class is already in the ontology signature, `GET /entity?iri=` on the class IRI also includes `connectorIndividuals`. A commit declares the class only when `newClassName` is set.

            `GET /connectors/actions?app=github` returns read actions only (`id`, `description`, `outputFields`). Write and destructive actions are omitted and are not executable.

            List what is already configured with `GET /connectors/connections`, `GET /connectors/apps`, and `GET /connectors/mappings`. Do not put secrets in the request body or in `mapping.json`.
            """;
}
