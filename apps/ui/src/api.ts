export type EngineInfo = { port: number; token: string };

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

let cached: EngineInfo | null = null;

async function engine(): Promise<EngineInfo> {
  if (cached) {
    return cached;
  }
  try {
    const { invoke } = await import("@tauri-apps/api/core");
    cached = await invoke<EngineInfo>("engine_info");
  } catch {
    cached = { port: 7717, token: "dev" };
  }
  return cached;
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const info = await engine();
  let response: Response;
  try {
    response = await fetch(`http://127.0.0.1:${info.port}${path}`, {
    ...init,
      headers: {
        "X-Engine-Token": info.token,
        "Content-Type": "application/json",
        ...(init?.headers ?? {}),
      },
    });
  } catch {
    throw new Error("Engine is not reachable. Start it with ./scripts/dev.sh");
  }
  if (!response.ok) {
    const body = await response.text();
    try {
      const parsed = JSON.parse(body) as { error?: string };
      throw new Error(parsed.error ?? body);
    } catch (error) {
      throw error instanceof Error ? error : new Error(body);
    }
  }
  return response.json() as Promise<T>;
}

export const api = {
  open: (path: string) =>
    call<OntologyInfo>("/ontology/open", {
      method: "POST",
      body: JSON.stringify({ path }),
    }),
  hierarchy: (parent?: string, inferred?: boolean) => {
    const params = new URLSearchParams();
    if (parent) {
      params.set("parent", parent);
    }
    if (inferred) {
      params.set("inferred", "true");
    }
    const query = params.toString();
    return call<{ children: TreeNode[] }>(`/hierarchy${query ? `?${query}` : ""}`);
  },
  entity: (iri: string) =>
    call<EntityInfo>(`/entity?iri=${encodeURIComponent(iri)}`),
  graph: (root: string | undefined, depth: number, inferred?: boolean) =>
    call<GraphSlice>(
      `/graph?depth=${depth}${root ? `&root=${encodeURIComponent(root)}` : ""}${inferred ? "&inferred=true" : ""}`,
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
  validateAxiom: (text: string) =>
    call<{ ok: boolean; axioms: string[]; unknown?: string[]; error?: string }>(
      "/axiom/validate",
      { method: "POST", body: JSON.stringify({ text }) },
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
    call<EditState & { action: string; summary: string[] }>("/action/commit", {
      method: "POST",
      body: JSON.stringify({ action, params, reason }),
    }),
  audit: () => call<{ rows: AuditRow[] }>("/audit"),
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
  plugins: () =>
    call<{ plugins: { name: string; permissions: string[]; actions: { name: string; description: string }[] }[] }>(
      "/plugins",
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
  search: (query: string, kind?: string) => {
    const params = new URLSearchParams({ q: query });
    if (kind) {
      params.set("kind", kind);
    }
    return call<{ results: SearchResult[] }>(`/search?${params.toString()}`);
  },
};
