export type EngineInfo = { port: number; token: string; url?: string };

export type OntologyInfo = {
  iri: string;
  path: string;
  axiomCount: number;
  entityCount: number;
};

export type TreeNode = {
  iri: string;
  label: string;
  hasChildren: boolean;
};

export type Ref = {
  iri: string;
  label: string;
};

export type EntityInfo = {
  iri: string;
  label: string;
  kind: string;
  marking: string;
  usage: number;
  superClasses: Ref[];
  subClasses: Ref[];
  domains: Ref[];
  ranges: Ref[];
  types: Ref[];
  annotations: string[];
  axioms: string[];
};

export type EvidenceRow = {
  ts: string;
  action: string;
  source: string;
  author: string;
  axiom: string;
  confidence: number;
};

export type WhyResult = {
  axiom: string;
  status: "asserted" | "inferred";
  evidence: EvidenceRow[];
  supporting?: string[];
  note?: string;
};

export type ActionPreview = {
  action: string;
  summary: string[];
  changes: string[];
};

export type ActionCommit = EditState & { action: string; summary: string[] };

/** Datatype filler inside a data-property restriction. */
export type DatatypeFiller = { kind: "datatype"; iri: string };

/**
 * Class-expression tree accepted by add-subclass-expression and add-equivalent-class.
 * Data-property some/only/min/max/exactly use a DatatypeFiller as filler.
 */
export type ClassExpression =
  | { kind: "class"; iri: string }
  | { kind: "some" | "only"; property: string; filler: ClassExpression | DatatypeFiller }
  | {
      kind: "min" | "max" | "exactly";
      property: string;
      cardinality: number;
      filler?: ClassExpression | DatatypeFiller;
    }
  | { kind: "intersection" | "union"; operands: ClassExpression[] }
  | { kind: "complement"; operand: ClassExpression };

export type PropertyCharacteristic =
  | "functional"
  | "inverse-functional"
  | "transitive"
  | "symmetric"
  | "asymmetric"
  | "reflexive"
  | "irreflexive"
  | "inverse"
  | "chain";

export type AuditRow = EvidenceRow & {
  entities: string[];
  actionName?: string;
  reason?: string;
  changes?: string[];
};

export type BranchInfo = {
  name: string;
  axioms: number;
  createdAt?: string;
  active: boolean;
};

export type BranchDiff = {
  branch: string;
  added: string[];
  removed: string[];
  inferredAdded: string[];
  inferredRemoved: string[];
};

export type EditState = {
  dirty: boolean;
  canUndo: boolean;
  canRedo: boolean;
};

export type GraphNode = {
  iri: string;
  label: string;
  depth: number;
};

export type GraphSlice = {
  nodes: GraphNode[];
  edges: { source: string; target: string }[];
};

export type SearchResult = {
  iri: string;
  label: string;
  kind: string;
};

export type ConnectorApp = {
  id: string;
  name: string;
  authType: string;
  locallyExecutable: boolean;
};

export type ConnectorConnection = {
  service: string;
  name: string;
  authType: string;
  status: "connected" | "reconnect";
  appId?: string;
};

export type ConnectorAction = {
  id: string;
  description: string;
  outputFields: string[];
};

export type ConnectorMappingBody = {
  connectionName: string;
  service: string;
  actionId: string;
  classIri?: string;
  newClassName?: string;
  idField: string;
  fieldMap: Record<string, string>;
  actionInput?: Record<string, unknown>;
  sampleLimit?: number;
};

export type ConnectorMappingPreview = {
  classIri: string;
  individuals: EntityInfo[];
  stored: boolean;
};

export type ConnectorGatewayList<T> = {
  gatewayAvailable: boolean;
  error?: string;
} & T;

let cached: EngineInfo | null = null;

async function engine(): Promise<EngineInfo> {
  if (cached) {
    return cached;
  }
  try {
    const { invoke } = await import("@tauri-apps/api/core");
    cached = await invoke<EngineInfo>("engine_info");
  } catch {
    const env = import.meta.env;
    const rawPort = Number.parseInt(String(env.VITE_ENGINE_PORT ?? ""), 10);
    const port = Number.isInteger(rawPort) && rawPort > 0 && rawPort <= 65535 ? rawPort : 7717;
    const token =
      typeof env.VITE_ENGINE_TOKEN === "string" && env.VITE_ENGINE_TOKEN.length > 0
        ? env.VITE_ENGINE_TOKEN
        : "dev";
    const url =
      typeof env.VITE_ENGINE_URL === "string" && env.VITE_ENGINE_URL.trim().length > 0
        ? env.VITE_ENGINE_URL.trim().replace(/\/+$/, "")
        : undefined;
    cached = { port, token, url };
  }
  return cached;
}

export function isAbortError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "name" in error &&
    (error as { name: unknown }).name === "AbortError"
  );
}

const DEFAULT_TIMEOUT_MS = 30_000;

function withTimeoutSignal(
  externalSignal?: AbortSignal,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): { signal: AbortSignal; cleanup: () => void } {
  const timeoutSignal =
    typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function"
      ? AbortSignal.timeout(timeoutMs)
      : undefined;

  if (timeoutSignal && externalSignal && typeof AbortSignal.any === "function") {
    return { signal: AbortSignal.any([externalSignal, timeoutSignal]), cleanup: () => {} };
  }
  if (timeoutSignal && !externalSignal) {
    return { signal: timeoutSignal, cleanup: () => {} };
  }

  const controller = new AbortController();
  const onExternalAbort = () => controller.abort(externalSignal?.reason);
  if (externalSignal) {
    if (externalSignal.aborted) {
      controller.abort(externalSignal.reason);
    } else {
      externalSignal.addEventListener("abort", onExternalAbort, { once: true });
    }
  }
  const timer = setTimeout(() => {
    controller.abort(new DOMException("Request timed out", "TimeoutError"));
  }, timeoutMs);

  return {
    signal: controller.signal,
    cleanup: () => {
      clearTimeout(timer);
      externalSignal?.removeEventListener("abort", onExternalAbort);
    },
  };
}

async function call<T>(
  path: string,
  init?: RequestInit & { timeoutMs?: number },
): Promise<T> {
  const info = await engine();
  const { timeoutMs, signal: externalSignal, ...fetchInit } = init ?? {};
  const { signal, cleanup } = withTimeoutSignal(externalSignal ?? undefined, timeoutMs);
  let response: Response;
  try {
    const baseUrl = info.url ?? `http://127.0.0.1:${info.port}`;
    response = await fetch(`${baseUrl}${path}`, {
      ...fetchInit,
      signal,
      headers: {
        "X-Engine-Token": info.token,
        "Content-Type": "application/json",
        ...(fetchInit.headers ?? {}),
      },
    });
  } catch (error) {
    const name =
      typeof error === "object" && error !== null && "name" in error
        ? (error as { name: unknown }).name
        : undefined;
    if (name === "AbortError" || name === "TimeoutError") {
      throw error;
    }
    throw new Error("Engine is not reachable. Check that the engine is running.");
  } finally {
    cleanup();
  }
  if (!response.ok) {
    const body = await response.text();
    let parsedError: string | undefined;
    try {
      const parsed = JSON.parse(body) as { error?: unknown };
      if (typeof parsed?.error === "string" && parsed.error.length > 0) {
        parsedError = parsed.error;
      }
    } catch {
      // Non-JSON error body; preserve HTTP status and raw body below.
    }
    throw new Error(
      parsedError ??
        (body ? `HTTP ${response.status}: ${body}` : `HTTP ${response.status} ${response.statusText}`),
    );
  }
  return response.json() as Promise<T>;
}

function postAction<T>(path: "/action/preview" | "/action/commit", action: string, params: object, reason?: string) {
  return call<T>(path, {
    method: "POST",
    body: JSON.stringify(reason === undefined ? { action, params } : { action, params, reason }),
  });
}

export const api = {
  open: (path: string) =>
    call<OntologyInfo>("/ontology/open", {
      method: "POST",
      body: JSON.stringify({ path }),
    }),
  hierarchy: (parent?: string, inferred?: boolean, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (parent) {
      params.set("parent", parent);
    }
    if (inferred) {
      params.set("inferred", "true");
    }
    const query = params.toString();
    return call<{ children: TreeNode[] }>(`/hierarchy${query ? `?${query}` : ""}`, { signal });
  },
  entity: (iri: string, signal?: AbortSignal) =>
    call<EntityInfo>(`/entity?iri=${encodeURIComponent(iri)}`, { signal }),
  graph: (root: string | undefined, depth: number, inferred?: boolean, signal?: AbortSignal) =>
    call<GraphSlice>(
      `/graph?depth=${depth}${root ? `&root=${encodeURIComponent(root)}` : ""}${inferred ? "&inferred=true" : ""}`,
      { signal },
    ),
  startReasoner: () => call<{ running: boolean; name: string }>("/reasoner/start", { method: "POST" }),
  stopReasoner: () => call<{ running: boolean }>("/reasoner/stop", { method: "POST" }),
  addSubclass: (sub: string, sup: string) =>
    call<EditState>("/axiom/subclass", {
      method: "POST",
      body: JSON.stringify({ sub, sup }),
    }),
  removeSubclass: (sub: string, sup: string) =>
    call<EditState>("/axiom/subclass/remove", {
      method: "POST",
      body: JSON.stringify({ sub, sup }),
    }),
  validateAxiom: (text: string, signal?: AbortSignal) =>
    call<{ ok: boolean; axioms: string[]; unknown?: string[]; error?: string }>(
      "/axiom/validate",
      { method: "POST", body: JSON.stringify({ text }), signal },
    ),
  addAxiomText: (text: string) =>
    call<EditState & { added: string[] }>("/axiom/add-text", {
      method: "POST",
      body: JSON.stringify({ text }),
    }),
  removeRenderedAxiom: (iri: string, text: string) =>
    call<EditState>("/axiom/remove-rendered", {
      method: "POST",
      body: JSON.stringify({ iri, text }),
    }),
  assistant: (question: string, entityIri?: string | null) =>
    call<{ answer: string }>("/assistant", {
      method: "POST",
      body: JSON.stringify({ question, entityIri: entityIri ?? null }),
    }),
  previewAction: (action: string, params: Record<string, string>) =>
    call<ActionPreview>("/action/preview", {
      method: "POST",
      body: JSON.stringify({ action, params }),
    }),
  commitAction: (
    action: string,
    params: Record<string, string>,
    reason?: string,
  ) =>
    call<ActionCommit>("/action/commit", {
      method: "POST",
      body: JSON.stringify({ action, params, reason }),
    }),
  previewSubclassExpression: (iri: string, expression: ClassExpression) =>
    postAction<ActionPreview>("/action/preview", "add-subclass-expression", { iri, expression }),
  commitSubclassExpression: (iri: string, expression: ClassExpression, reason?: string) =>
    postAction<ActionCommit>("/action/commit", "add-subclass-expression", { iri, expression }, reason),
  previewEquivalentClass: (iri: string, expression: ClassExpression) =>
    postAction<ActionPreview>("/action/preview", "add-equivalent-class", { iri, expression }),
  commitEquivalentClass: (iri: string, expression: ClassExpression, reason?: string) =>
    postAction<ActionCommit>("/action/commit", "add-equivalent-class", { iri, expression }, reason),
  previewPropertyCharacteristic: (
    iri: string,
    characteristic: PropertyCharacteristic,
    extra?: { inverse?: string; chain?: string[] },
  ) =>
    postAction<ActionPreview>("/action/preview", "add-property-characteristic", {
      iri,
      characteristic,
      ...extra,
    }),
  commitPropertyCharacteristic: (
    iri: string,
    characteristic: PropertyCharacteristic,
    extra?: { inverse?: string; chain?: string[] },
    reason?: string,
  ) =>
    postAction<ActionCommit>(
      "/action/commit",
      "add-property-characteristic",
      { iri, characteristic, ...extra },
      reason,
    ),
  previewClassAssertion: (iri: string, classIri: string) =>
    postAction<ActionPreview>("/action/preview", "add-type", { iri, class: classIri }),
  commitClassAssertion: (iri: string, classIri: string, reason?: string) =>
    postAction<ActionCommit>("/action/commit", "add-type", { iri, class: classIri }, reason),
  previewObjectAssertion: (iri: string, property: string, target: string) =>
    postAction<ActionPreview>("/action/preview", "add-object-assertion", { iri, property, target }),
  commitObjectAssertion: (iri: string, property: string, target: string, reason?: string) =>
    postAction<ActionCommit>("/action/commit", "add-object-assertion", { iri, property, target }, reason),
  previewDataAssertion: (iri: string, property: string, value: string, datatype?: string) =>
    postAction<ActionPreview>("/action/preview", "add-data-assertion", {
      iri,
      property,
      value,
      ...(datatype ? { datatype } : {}),
    }),
  commitDataAssertion: (iri: string, property: string, value: string, datatype?: string, reason?: string) =>
    postAction<ActionCommit>(
      "/action/commit",
      "add-data-assertion",
      { iri, property, value, ...(datatype ? { datatype } : {}) },
      reason,
    ),
  previewSameAs: (iri: string, other: string) =>
    postAction<ActionPreview>("/action/preview", "add-same-as", { iri, other }),
  commitSameAs: (iri: string, other: string, reason?: string) =>
    postAction<ActionCommit>("/action/commit", "add-same-as", { iri, other }, reason),
  previewDifferentFrom: (iri: string, other: string) =>
    postAction<ActionPreview>("/action/preview", "add-different-from", { iri, other }),
  commitDifferentFrom: (iri: string, other: string, reason?: string) =>
    postAction<ActionCommit>("/action/commit", "add-different-from", { iri, other }, reason),
  audit: (signal?: AbortSignal) => call<{ rows: AuditRow[] }>("/audit", { signal }),
  setMarking: (iri: string, marking: string) =>
    call<{ iri: string; marking: string }>("/marking/set", {
      method: "POST",
      body: JSON.stringify({ iri, marking }),
    }),
  exportOntology: (path: string, includeRestricted = false) =>
    call<{ path: string; axioms: number; skipped: number }>("/export", {
      method: "POST",
      body: JSON.stringify({ path, includeRestricted }),
    }),
  plugins: (signal?: AbortSignal) =>
    call<{
      plugins: {
        name: string;
        permissions: string[];
        actions: { name: string; description: string; params: Record<string, string> }[];
      }[];
    }>(
      "/plugins",
      { signal },
    ),
  installPlugin: (url: string, sha256: string) =>
    call<{ installed: string; plugins: number }>("/plugins/install", {
      method: "POST",
      body: JSON.stringify({ url, sha256 }),
    }),
  branches: () => call<{ branches: BranchInfo[] }>("/branch/list"),
  createBranch: (name: string) =>
    call<{ name: string; axioms: number }>("/branch/create", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  switchBranch: (name: string) =>
    call<{ branch: string; axioms: number }>("/branch/switch", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  branchDiff: (name: string) =>
    call<BranchDiff>(`/branch/diff?name=${encodeURIComponent(name)}`),
  promoteBranch: (name: string, reason: string) =>
    call<{ promoted: string; changes: number }>("/branch/promote", {
      method: "POST",
      body: JSON.stringify({ name, reason }),
    }),
  evidence: (iri: string) =>
    call<{ rows: EvidenceRow[]; import: EvidenceRow | null }>(
      `/evidence?iri=${encodeURIComponent(iri)}`,
    ),
  why: (iri: string, axiom: string) =>
    call<WhyResult>(
      `/why?iri=${encodeURIComponent(iri)}&axiom=${encodeURIComponent(axiom)}`,
    ),
  undo: () => call<EditState>("/undo", { method: "POST" }),
  redo: () => call<EditState>("/redo", { method: "POST" }),
  history: () => call<EditState>("/history"),
  save: (path?: string) =>
    call<EditState & { path: string }>("/ontology/save", {
      method: "POST",
      body: JSON.stringify(path ? { path } : {}),
    }),
  search: (query: string, kind?: string, signal?: AbortSignal) => {
    const params = new URLSearchParams({ q: query });
    if (kind) {
      params.set("kind", kind);
    }
    return call<{ results: SearchResult[] }>(`/search?${params.toString()}`, { signal });
  },
  connectorApps: (query?: string, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (query) {
      params.set("q", query);
    }
    const suffix = params.toString();
    return call<ConnectorGatewayList<{ apps: ConnectorApp[]; truncated?: boolean }>>(
      `/connectors/apps${suffix ? `?${suffix}` : ""}`,
      { signal, timeoutMs: 8_000 },
    );
  },
  connectorConnections: (signal?: AbortSignal) =>
    call<ConnectorGatewayList<{ connections: ConnectorConnection[] }>>("/connectors/connections", {
      signal,
      timeoutMs: 8_000,
    }),
  connectorActions: (app: string, signal?: AbortSignal) =>
    call<ConnectorGatewayList<{ actions: ConnectorAction[] }>>(
      `/connectors/actions?app=${encodeURIComponent(app)}`,
      { signal, timeoutMs: 8_000 },
    ),
  connectorMappings: (signal?: AbortSignal) =>
    call<{ mappings: unknown[]; gatewayAvailable: boolean }>("/connectors/mappings", { signal }),
  previewConnectorMapping: (body: ConnectorMappingBody) =>
    call<ConnectorMappingPreview>("/connectors/mapping/preview", {
      method: "POST",
      body: JSON.stringify(body),
      timeoutMs: 15_000,
    }),
  commitConnectorMapping: (body: ConnectorMappingBody) =>
    call<{ classIri: string; stored: boolean }>("/connectors/mapping/commit", {
      method: "POST",
      body: JSON.stringify(body),
      timeoutMs: 15_000,
    }),
};
