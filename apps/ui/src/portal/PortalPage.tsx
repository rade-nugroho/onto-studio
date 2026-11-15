import { useEffect, useState } from "react";
import {
  ApiReferenceReact,
  type AnyApiReferenceConfiguration,
} from "@scalar/api-reference-react";
import "@scalar/api-reference-react/style.css";

/**
 * Developer portal at /portal. Kept outside src/app/ide because Scalar's
 * reference markup is not something Astryx doctor accepts.
 */
export function portalEngineTarget(search: string): { url: string; token: string } {
  const params = new URLSearchParams(search);
  const engine = (params.get("engine") ?? "http://127.0.0.1:7717").replace(/\/+$/, "");
  const token = params.get("token") ?? "dev";
  return { url: `${engine}/openapi.json`, token };
}

const PREVIEW = `{
  "connectionName": "default",
  "service": "github",
  "actionId": "github.list_issues",
  "classIri": "http://example.org#Issue",
  "idField": "id",
  "fieldMap": { "title": "http://example.org#title" },
  "actionInput": {},
  "sampleLimit": 2
}`;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function PortalPage() {
  const [spec, setSpec] = useState<Record<string, unknown> | null>(null);
  const [specError, setSpecError] = useState<string | null>(null);

  useEffect(() => {
    const target = portalEngineTarget(window.location.search);
    const controller = new AbortController();
    void fetch(target.url, {
      headers: { "X-Engine-Token": target.token },
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`OpenAPI request failed (${response.status})`);
        }
        const document: unknown = await response.json();
        if (!isRecord(document)) {
          throw new Error("OpenAPI document was not an object");
        }
        setSpec(document);
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        setSpecError(error instanceof Error ? error.message : "Could not load the OpenAPI document");
      });
    return () => controller.abort();
  }, []);

  const configuration = spec
    ? ({ content: spec } as AnyApiReferenceConfiguration)
    : null;

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-3xl flex-col gap-8 bg-background px-6 py-8 text-foreground">
      <header className="flex flex-col gap-3">
        <h1 className="text-2xl font-semibold">Onto Studio developer portal</h1>
        <p className="text-muted-foreground">
          Local ontology workbench, connector mappings, and the engine API.
        </p>
        <nav className="flex gap-4" aria-label="Portal sections">
          <a className="text-primary underline" href="#introduction">
            Introduction
          </a>
          <a className="text-primary underline" href="#reference">
            API reference
          </a>
          <a className="text-primary underline" href="#example">
            Example case
          </a>
        </nav>
      </header>

      <section id="introduction" className="flex flex-col gap-3">
        <h2 className="text-xl font-semibold">Introduction</h2>
        <p>
          Onto Studio is a local ontology workbench. The desktop shell starts the Java engine on
          127.0.0.1. Ontology edits go through that engine and are recorded in{" "}
          <code>.onto-studio/evidence.jsonl</code>. Connector mappings live beside the ontology in{" "}
          <code>.onto-studio/mapping.json</code>. A mapping names a connection and a read action.
          It does not copy gateway rows into the OWL file.
        </p>
        <p>
          Preview (<code>POST /connectors/mapping/preview</code>) compares a local OWL class with a
          live connector read. The engine calls the OpenConnector read action and returns sample
          individuals. The response field <code>stored</code> is false. Preview does not write{" "}
          <code>mapping.json</code> and does not add those individuals to the OWL file. Commit (
          <code>POST /connectors/mapping/commit</code>) stores the mapping (<code>classIri</code>,{" "}
          <code>service</code>, <code>connectionName</code>, <code>actionId</code>,{" "}
          <code>idField</code>, <code>fieldMap</code>, <code>actionInput</code>).{" "}
          <code>stored</code> is true. A <code>newClassName</code> can declare the class. Gateway
          rows stay out of the OWL file. Later <code>GET /search</code> and <code>GET /entity</code>{" "}
          read them live.
        </p>
        <p>
          Adaptive checks — device posture, risk, and how many factors to require — are configured
          in Keycloak with the{" "}
          <a className="text-primary underline" href="https://github.com/mabartos/keycloak-adaptive-authn">
            keycloak-adaptive-authn
          </a>{" "}
          extension. Policies stay in Keycloak. This engine does not embed that extension. When{" "}
          <code>KEYCLOAK_ISSUER</code> is set, the engine only accepts the access token Keycloak
          issues after those checks. It validates issuer, expiry, and audience (
          <code>KEYCLOAK_AUDIENCE</code>, default <code>onto-studio</code>) using the issuer's
          JWKS. <code>GET /health</code> and <code>GET /openapi.json</code> stay anonymous in that
          mode. When <code>KEYCLOAK_ISSUER</code> is unset, requests keep using the{" "}
          <code>X-Engine-Token</code> header.
        </p>
        <p>
          Connector credentials stay in{" "}
          <a className="text-primary underline" href="https://github.com/Infisical/infisical">
            Infisical
          </a>
          . When <code>INFISICAL_CLIENT_ID</code>, <code>INFISICAL_CLIENT_SECRET</code>, and{" "}
          <code>INFISICAL_PROJECT_ID</code> are all set, the engine logs in with universal auth (
          <code>POST /api/v1/auth/universal-auth/login</code>) and reads{" "}
          <code>OPEN_CONNECTOR_TOKEN</code> from{" "}
          <code>GET /api/v4/secrets/OPEN_CONNECTOR_TOKEN</code>.{" "}
          <code>INFISICAL_SITE_URL</code> defaults to <code>https://app.infisical.com</code>.{" "}
          <code>INFISICAL_ENVIRONMENT</code> defaults to <code>dev</code>. When those three
          variables are unset, <code>OPEN_CONNECTOR_TOKEN</code> still comes from the environment.
          Secret values are not written to <code>mapping.json</code>, <code>evidence.jsonl</code>,
          logs, or the OpenAPI document. Write and destructive connector actions stay forbidden.
        </p>
      </section>

      <section id="reference" className="flex w-full flex-col gap-3" aria-label="API reference">
        <h2 className="text-xl font-semibold">API reference</h2>
        <p>
          Scalar renders the engine OpenAPI document from <code>GET /openapi.json</code>. This
          section is that document, not a second list of routes.
        </p>
        {configuration ? (
          <ApiReferenceReact configuration={configuration} />
        ) : (
          <p>{specError ?? "Loading the engine OpenAPI document."}</p>
        )}
      </section>

      <section id="example" className="flex flex-col gap-3">
        <h2 className="text-xl font-semibold">Example case</h2>
        <p>
          Open an ontology with <code>POST /ontology/open</code> and body{" "}
          <code>{`{ "path" }`}</code> set to the OWL file. Then connect a read action, commit the
          mapping, and read individuals. Send <code>X-Engine-Token</code> when{" "}
          <code>KEYCLOAK_ISSUER</code> is unset. Send <code>Authorization: Bearer</code> when it is
          set.
        </p>
        <ol className="flex list-decimal flex-col gap-3 pl-6">
          <li>
            <code>GET /connectors/connections</code> returns <code>connections</code>. Each row has{" "}
            <code>service</code>, <code>name</code> (the gateway alias), <code>authType</code>, and{" "}
            <code>status</code>. Use <code>name</code> as <code>connectionName</code>.
          </li>
          <li>
            <code>GET /connectors/actions?app=github</code> returns read actions only. Each action
            has <code>id</code>, <code>description</code>, and <code>outputFields</code>. Write and
            destructive actions are omitted. Pick <code>github.list_issues</code> when{" "}
            <code>outputFields</code> includes <code>id</code> and <code>title</code>.
          </li>
          <li>
            Preview does not store anything. <code>POST /connectors/mapping/preview</code> with the
            body below returns <code>classIri</code>, <code>individuals</code>, and{" "}
            <code>stored: false</code>. <code>mapping.json</code> is not written. The OWL file does
            not gain individuals.
            <pre className="overflow-x-auto rounded-md bg-muted p-4 text-sm">
              <code>{PREVIEW}</code>
            </pre>
          </li>
          <li>
            <code>POST /connectors/mapping/commit</code> accepts the same JSON. The response has{" "}
            <code>classIri</code>, <code>mapping</code>, and <code>stored: true</code>.{" "}
            <code>mapping</code> contains <code>classIri</code>, <code>service</code>,{" "}
            <code>connectionName</code>, <code>actionId</code>, <code>idField</code>,{" "}
            <code>fieldMap</code>, and <code>actionInput</code>. That object is what{" "}
            <code>.onto-studio/mapping.json</code> stores. Pass <code>newClassName</code> only when
            the class should be declared. Individuals are still not written into the OWL file.
          </li>
          <li>
            <code>GET /search?q=1&kind=individual</code> matches <code>label</code> and{" "}
            <code>iri</code>. <code>label</code> is the <code>idField</code> value (<code>1</code>
            ). The individual IRI is the class namespace plus <code>#</code> plus{" "}
            <code>{`{service}-{id}`}</code>, here <code>http://example.org#github-1</code>. Each
            result has <code>iri</code>, <code>label</code>, and <code>kind: individual</code>.
          </li>
          <li>
            <code>GET /entity?iri=http://example.org#github-1</code> returns that live individual:{" "}
            <code>iri</code>, <code>label</code>, <code>kind</code>, <code>types</code>,{" "}
            <code>axioms</code>, and <code>connectorSource</code> (<code>service</code>,{" "}
            <code>actionId</code>, <code>connectionName</code>). The title from <code>fieldMap</code>{" "}
            shows up in <code>axioms</code>, not as the search label.{" "}
            <code>GET /entity?iri=http://example.org#Issue</code> includes{" "}
            <code>connectorIndividuals</code> when that class is already in the ontology. None of
            those rows are stored in the OWL file.
          </li>
        </ol>
      </section>
    </main>
  );
}
