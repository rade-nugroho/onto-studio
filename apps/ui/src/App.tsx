import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type EntityInfo, type OntologyInfo, type SearchResult, type TreeNode } from "./api";
import GraphView from "./GraphView";

type ChildrenByIri = Record<string, TreeNode[]>;

export default function App() {
  const [ontology, setOntology] = useState<OntologyInfo | null>(null);
  const [children, setChildren] = useState<ChildrenByIri>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [entity, setEntity] = useState<EntityInfo | null>(null);
  const [query, setQuery] = useState("");
  const [view, setView] = useState<"tree" | "graph">("tree");
  const [depth, setDepth] = useState(2);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [error, setError] = useState<string | null>(null);

  const loadChildren = useCallback(async (parent?: string) => {
    const key = parent ?? "root";
    const response = await api.hierarchy(parent);
    setChildren((current) => ({ ...current, [key]: response.children }));
    return response.children;
  }, []);

  useEffect(() => {
    loadChildren().catch((e: Error) => setError(e.message));
  }, [loadChildren]);

  const openFile = useCallback(async () => {
    setError(null);
    try {
      const { open } = await import("@tauri-apps/plugin-dialog");
      const path = await open({
        multiple: false,
        filters: [{ name: "Ontology", extensions: ["owl", "ttl", "rdf", "xml", "ofn"] }],
      });
      if (typeof path === "string") {
        const info = await api.open(path);
        setOntology(info);
        setSelected(null);
        setEntity(null);
        setExpanded(new Set());
        setChildren({});
        await loadChildren();
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, [loadChildren]);

  const toggle = useCallback(
    async (node: TreeNode) => {
      const next = new Set(expanded);
      if (next.has(node.iri)) {
        next.delete(node.iri);
      } else {
        next.add(node.iri);
        if (!children[node.iri]) {
          await loadChildren(node.iri);
        }
      }
      setExpanded(next);
    },
    [children, expanded, loadChildren],
  );

  const select = useCallback(async (iri: string) => {
    setSelected(iri);
    try {
      setEntity(await api.entity(iri));
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  const runSearch = useCallback(async (value: string) => {
    setQuery(value);
    if (value.trim().length < 2) {
      setResults([]);
      return;
    }
    try {
      setResults((await api.search(value)).results);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  const roots = useMemo(() => children["root"] ?? [], [children]);

  return (
    <div className="app">
      <header className="bar">
        <div className="brand">Onto Studio</div>
        <button onClick={openFile}>Open ontology…</button>
        <div className="segmented">
          <button
            className={view === "tree" ? "active" : ""}
            onClick={() => setView("tree")}
          >
            Tree
          </button>
          <button
            className={view === "graph" ? "active" : ""}
            onClick={() => setView("graph")}
          >
            Graph
          </button>
        </div>
        {view === "graph" && (
          <select
            className="depth"
            value={depth}
            onChange={(event) => setDepth(Number(event.target.value))}
          >
            <option value={1}>1 level</option>
            <option value={2}>2 levels</option>
            <option value={3}>3 levels</option>
          </select>
        )}
        <input
          className="search"
          placeholder="Search classes, properties, individuals"
          value={query}
          onChange={(event) => runSearch(event.target.value)}
        />
        {ontology && (
          <span className="meta">
            {ontology.axiomCount.toLocaleString()} axioms · {ontology.entityCount} entities
          </span>
        )}
      </header>

      {results.length > 0 && (
        <div className="results">
          {results.map((result) => (
            <button key={result.iri} className="result" onClick={() => select(result.iri)}>
              <span className="kind">{result.kind}</span>
              {result.label}
            </button>
          ))}
        </div>
      )}

      <main className={view === "graph" ? "panes graph" : "panes"}>
        {view === "graph" ? (
          <div className="canvas">
            <GraphView
              depth={depth}
              selected={selected}
              onSelect={select}
              onError={setError}
            />
          </div>
        ) : (
        <aside className="tree">
          {roots.length === 0 && <div className="empty">No ontology open</div>}
          {roots.map((node) => (
            <TreeItem
              key={node.iri}
              node={node}
              depth={0}
              childrenByIri={children}
              expanded={expanded}
              selected={selected}
              onToggle={toggle}
              onSelect={select}
            />
          ))}
        </aside>
        )}

        <section className="detail">
          {!entity && <div className="empty">Select a class to see its axioms</div>}
          {entity && (
            <>
              <h2>{entity.label}</h2>
              <div className="iri">{entity.iri}</div>
              {entity.annotations.length > 0 && (
                <div className="block">
                  <h3>Annotations</h3>
                  {entity.annotations.map((line) => (
                    <div key={line} className="line">
                      {line}
                    </div>
                  ))}
                </div>
              )}
              <div className="block">
                <h3>Axioms</h3>
                {entity.axioms.map((line) => (
                  <div key={line} className="line axiom">
                    {line}
                  </div>
                ))}
              </div>
            </>
          )}
        </section>
      </main>

      {error && <div className="error">{error}</div>}
    </div>
  );
}

type TreeItemProps = {
  node: TreeNode;
  depth: number;
  childrenByIri: ChildrenByIri;
  expanded: Set<string>;
  selected: string | null;
  onToggle: (node: TreeNode) => void;
  onSelect: (iri: string) => void;
};

function TreeItem(props: TreeItemProps) {
  const { node, depth, childrenByIri, expanded, selected, onToggle, onSelect } = props;
  const isOpen = expanded.has(node.iri);
  const kids = childrenByIri[node.iri] ?? [];

  return (
    <div>
      <div
        className={`row${selected === node.iri ? " active" : ""}`}
        style={{ paddingLeft: 8 + depth * 14 }}
        onClick={() => onSelect(node.iri)}
      >
        <button
          className="twisty"
          onClick={(event) => {
            event.stopPropagation();
            if (node.hasChildren) {
              onToggle(node);
            }
          }}
        >
          {node.hasChildren ? (isOpen ? "▾" : "▸") : ""}
        </button>
        <span className="label">{node.label}</span>
      </div>
      {isOpen &&
        kids.map((child) => (
          <TreeItem
            key={child.iri}
            {...props}
            node={child}
            depth={depth + 1}
          />
        ))}
    </div>
  );
}
