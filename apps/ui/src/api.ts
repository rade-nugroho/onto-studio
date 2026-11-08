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

export type EntityInfo = {
  iri: string;
  label: string;
  annotations: string[];
  axioms: string[];
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
  const response = await fetch(`http://127.0.0.1:${info.port}${path}`, {
    ...init,
    headers: {
      "X-Engine-Token": info.token,
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (!response.ok) {
    throw new Error(await response.text());
  }
  return response.json() as Promise<T>;
}

export const api = {
  open: (path: string) =>
    call<OntologyInfo>("/ontology/open", {
      method: "POST",
      body: JSON.stringify({ path }),
    }),
  hierarchy: (parent?: string) =>
    call<{ children: TreeNode[] }>(
      `/hierarchy${parent ? `?parent=${encodeURIComponent(parent)}` : ""}`,
    ),
  entity: (iri: string) =>
    call<EntityInfo>(`/entity?iri=${encodeURIComponent(iri)}`),
  graph: (root: string | undefined, depth: number) =>
    call<GraphSlice>(
      `/graph?depth=${depth}${root ? `&root=${encodeURIComponent(root)}` : ""}`,
    ),
  search: (query: string) =>
    call<{ results: SearchResult[] }>(`/search?q=${encodeURIComponent(query)}`),
};
