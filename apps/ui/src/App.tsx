import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  api,
  isAbortError,
  type BranchDiff,
  type BranchInfo,
  type EditState,
  type EntityInfo,
  type OntologyInfo,
  type SearchResult,
  type TreeNode,
} from "./api";
import EntityPanel from "./EntityPanel";
import ErrorBoundary from "./ErrorBoundary";
import ModalDialog, { type DialogField } from "./ModalDialog";
import AppShell9 from "@/components/blocks/app-shell-9";
import CommandMenu1, { type Command } from "@/components/blocks/command-menu-1";
import AppSidebar2 from "@/components/blocks/app-sidebar-2";

import AssistantPanel from "./AssistantPanel";
import { OntologyIdePage } from "./app/ide";
import {
  ArrowDown01Icon,
  CheckListIcon,
  File02Icon,
  FlowConnectionIcon,
  LayoutTemplateIcon,
  Search01Icon,
  SparklesIcon,
} from "@hugeicons/core-free-icons";

const GraphView = lazy(() => import("./GraphView"));

export function shouldIgnoreGlobalShortcut(event: KeyboardEvent): boolean {
  const target = event.target;
  if (target instanceof HTMLElement) {
    const tag = target.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable) {
      return true;
    }
    if (target.closest('[role="dialog"]')) {
      return true;
    }
  }
  return document.querySelector('[role="dialog"]') !== null;
}

type ChildrenByIri = Record<string, TreeNode[]>;

function isIdleOntologyMessage(message: string): boolean {
  return message.replace(/\.$/, "").trim() === "No ontology is open";
}

export default function App() {
  const [ontology, setOntology] = useState<OntologyInfo | null>(null);
  const [children, setChildren] = useState<ChildrenByIri>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [entity, setEntity] = useState<EntityInfo | null>(null);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[]>([]);
  const [view, setView] = useState<"tree" | "graph">("tree");
  const [assistantOpen, setAssistantOpen] = useState(false);
  const [connectorsOpen, setConnectorsOpen] = useState(false);
  const [depth, setDepth] = useState(2);
  const [dirty, setDirty] = useState(false);
  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);
  const [reasoning, setReasoning] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [branches, setBranches] = useState<BranchInfo[]>([]);
  const [activeBranch, setActiveBranch] = useState("main");
  const [diff, setDiff] = useState<BranchDiff | null>(null);
  const [version, setVersion] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [entityLoading, setEntityLoading] = useState(false);
  const [dialogState, setDialogState] = useState<{
    title: string;
    description?: string;
    fields?: DialogField[];
    confirmLabel?: string;
    destructive?: boolean;
    resolve: (values: Record<string, string> | null) => void;
  } | null>(null);
  const searchAbortRef = useRef<AbortController | null>(null);
  const selectAbortRef = useRef<AbortController | null>(null);

  const openDialog = useCallback(
    (opts: {
      title: string;
      description?: string;
      fields?: DialogField[];
      confirmLabel?: string;
      destructive?: boolean;
    }): Promise<Record<string, string> | null> =>
      new Promise((resolve) => {
        setDialogState((prev) => {
          prev?.resolve(null);
          return { ...opts, resolve };
        });
      }),
    [],
  );

  useEffect(() => {
    return () => {
      searchAbortRef.current?.abort();
      selectAbortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!dirty) {
      return;
    }
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  const loadChildren = useCallback(
    async (parent?: string, signal?: AbortSignal) => {
      const key = parent ?? "root";
      if (!parent) {
        setTreeLoading(true);
      }
      try {
        const response = await api.hierarchy(parent, reasoning, signal);
        if (!signal?.aborted) {
          setChildren((current) => ({ ...current, [key]: response.children }));
        }
        return response.children;
      } finally {
        if (!parent && !signal?.aborted) {
          setTreeLoading(false);
        }
      }
    },
    [reasoning],
  );

  useEffect(() => {
    const controller = new AbortController();
    loadChildren(undefined, controller.signal).catch((e: unknown) => {
      if (isAbortError(e)) {
        return;
      }
      const message = e instanceof Error ? e.message : String(e);
      if (isIdleOntologyMessage(message)) {
        return;
      }
      setError(message);
    });
    return () => controller.abort();
  }, [loadChildren]);

  const openFile = useCallback(async () => {
    if (dirty) {
      const confirmed = await openDialog({
        title: "Discard unsaved changes?",
        description: "You have unsaved changes. Discard them and open another ontology?",
        confirmLabel: "Discard & Open",
        destructive: true,
      });
      if (!confirmed) {
        return;
      }
    }
    setError(null);
    setNotice(null);
    try {
      let path: string | null = null;
      const inTauri = "__TAURI_INTERNALS__" in window;
      if (inTauri) {
        try {
          const { open } = await import("@tauri-apps/plugin-dialog");
          path = await open({
            multiple: false,
            filters: [{ name: "Ontology", extensions: ["owl", "ttl", "rdf", "xml", "ofn"] }],
          });
        } catch {
          const res = await openDialog({
            title: "Open ontology",
            fields: [{ key: "path", label: "Ontology file path", placeholder: "/path/to/ontology.owl", required: true }],
            confirmLabel: "Open",
          });
          path = res?.path.trim() ?? null;
        }
      } else {
        const res = await openDialog({
          title: "Open ontology",
          fields: [{ key: "path", label: "Ontology file path", placeholder: "/path/to/ontology.owl", required: true }],
          confirmLabel: "Open",
        });
        path = res?.path.trim() ?? null;
      }
      if (typeof path === "string") {
        const info = await api.open(path);
        setOntology(info);
        setSelected(null);
        setEntity(null);
        setExpanded(new Set());
        setChildren({});
        setDirty(false);
        setCanUndo(false);
        setCanRedo(false);
        setDiff(null);
        setActiveBranch("main");
        setBranches((await api.branches()).branches);
        await loadChildren();
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, [dirty, loadChildren, openDialog]);

  const refreshBranches = useCallback(async () => {
    try {
      setBranches((await api.branches()).branches);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  const switchBranch = useCallback(
    async (name: string) => {
      if (dirty) {
        const confirmed = await openDialog({
          title: "Discard unsaved changes?",
          description: "You have unsaved changes. Discard them and switch branch?",
          confirmLabel: "Discard & Switch",
          destructive: true,
        });
        if (!confirmed) {
          return;
        }
      }
      setError(null);
      try {
        await api.switchBranch(name);
        setActiveBranch(name);
        setDirty(false);
        setDiff(null);
        setChildren({});
        setExpanded(new Set());
        setVersion((v) => v + 1);
        await refreshBranches();
        await loadChildren();
      } catch (e) {
        setError((e as Error).message);
      }
    },
    [dirty, loadChildren, openDialog, refreshBranches],
  );

  const newBranch = useCallback(async () => {
    const res = await openDialog({
      title: "New branch",
      fields: [{ key: "name", label: "Branch name", placeholder: "feature/my-proposal", required: true }],
      confirmLabel: "Create",
    });
    const name = res?.name.trim();
    if (!name) {
      return;
    }
    setError(null);
    try {
      await api.createBranch(name);
      await refreshBranches();
      await switchBranch(name);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [openDialog, refreshBranches, switchBranch]);

  const showDiff = useCallback(async () => {
    setError(null);
    try {
      setDiff(await api.branchDiff(activeBranch));
    } catch (e) {
      setError((e as Error).message);
    }
  }, [activeBranch]);

  const promote = useCallback(async () => {
    const res = await openDialog({
      title: `Promote branch "${activeBranch}"`,
      fields: [{ key: "reason", label: "Reason for promoting this branch", placeholder: "Reviewed and verified" }],
      confirmLabel: "Promote",
    });
    if (res === null) {
      return;
    }
    const reason = res.reason ?? "";
    setError(null);
    try {
      await api.promoteBranch(activeBranch, reason);
      setActiveBranch("main");
      setDiff(null);
      setVersion((v) => v + 1);
      await refreshBranches();
      await loadChildren();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [activeBranch, loadChildren, openDialog, refreshBranches]);

  const toggle = useCallback(
    async (node: TreeNode) => {
      const next = new Set(expanded);
      if (next.has(node.iri)) {
        next.delete(node.iri);
        setExpanded(next);
        return;
      }
      if (!children[node.iri]) {
        try {
          setError(null);
          await loadChildren(node.iri);
        } catch (e) {
          setError((e as Error).message);
          return;
        }
      }
      next.add(node.iri);
      setExpanded(next);
    },
    [children, expanded, loadChildren],
  );

  const select = useCallback(async (iri: string) => {
    selectAbortRef.current?.abort();
    const controller = new AbortController();
    selectAbortRef.current = controller;
    setSelected(iri);
    setEntityLoading(true);
    setError(null);
    try {
      const info = await api.entity(iri, controller.signal);
      if (!controller.signal.aborted) {
        setEntity(info);
      }
    } catch (e) {
      if (!isAbortError(e)) {
        setError((e as Error).message);
      }
    } finally {
      if (!controller.signal.aborted) {
        setEntityLoading(false);
      }
    }
  }, []);

  const applyEditState = useCallback((state: EditState) => {
    setDirty(state.dirty);
    setCanUndo(state.canUndo);
    setCanRedo(state.canRedo);
  }, []);

  const refreshSelected = useCallback(async () => {
    if (!selected) {
      return;
    }
    try {
      setEntity(await api.entity(selected));
    } catch (e) {
      setError((e as Error).message);
    }
  }, [selected]);

  const onEdited = useCallback(
    (state: EditState) => {
      setError(null);
      applyEditState(state);
      setVersion((v) => v + 1);
      void refreshSelected();
    },
    [applyEditState, refreshSelected],
  );

  const toggleReasoner = useCallback(async () => {
    setError(null);
    try {
      if (reasoning) {
        await api.stopReasoner();
        setReasoning(false);
      } else {
        await api.startReasoner();
        setReasoning(true);
      }
      setChildren({});
      setExpanded(new Set());
      setVersion((v) => v + 1);
      await loadChildren();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [loadChildren, reasoning]);

  const save = useCallback(async () => {
    setError(null);
    try {
      const saved = await api.save();
      applyEditState(saved);
      setNotice(`Saved ${saved.path}`);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [applyEditState]);

  const undo = useCallback(async () => {
    setError(null);
    try {
      applyEditState(await api.undo());
      setVersion((v) => v + 1);
      await refreshSelected();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [applyEditState, refreshSelected]);

  const redo = useCallback(async () => {
    setError(null);
    try {
      applyEditState(await api.redo());
      setVersion((v) => v + 1);
      await refreshSelected();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [applyEditState, refreshSelected]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (shouldIgnoreGlobalShortcut(event)) {
        return;
      }
      const mod = event.metaKey || event.ctrlKey;
      if (!mod) {
        return;
      }
      const key = event.key.toLowerCase();
      if (key === "z" && event.shiftKey) {
        event.preventDefault();
        redo();
      } else if (key === "z") {
        event.preventDefault();
        undo();
      } else if (key === "s") {
        event.preventDefault();
        save();
      } else if (key === "o") {
        event.preventDefault();
        openFile();
      } else if (key === "1") {
        event.preventDefault();
        setView("tree");
      } else if (key === "2") {
        event.preventDefault();
        setView("graph");
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [openFile, redo, save, undo]);

  const runSearch = useCallback(async (value: string) => {
    setQuery(value);
    searchAbortRef.current?.abort();
    if (value.trim().length < 2) {
      setResults([]);
      return;
    }
    const controller = new AbortController();
    searchAbortRef.current = controller;
    setError(null);
    try {
      const response = await api.search(value, undefined, controller.signal);
      if (!controller.signal.aborted) {
        setResults(response.results);
      }
    } catch (e) {
      if (!isAbortError(e)) {
        setError((e as Error).message);
      }
    }
  }, []);

  const commands = useMemo<Command[]>(
    () => [
      { id: "open", group: "Ontology", title: "Open ontology…", icon: File02Icon, shortcut: "⌘O" },
      { id: "save", group: "Ontology", title: "Save ontology", icon: CheckListIcon, shortcut: "⌘S" },
      { id: "undo", group: "Edit", title: "Undo", icon: ArrowDown01Icon, shortcut: "⌘Z" },
      { id: "redo", group: "Edit", title: "Redo", icon: ArrowDown01Icon, shortcut: "⌘⇧Z" },
      {
        id: "reasoner",
        group: "View",
        title: reasoning ? "Stop reasoner" : "Start reasoner",
        icon: SparklesIcon,
      },
      { id: "tree", group: "View", title: "Show class tree", icon: LayoutTemplateIcon },
      { id: "graph", group: "View", title: "Show class graph", icon: LayoutTemplateIcon },
      {
        id: "assistant",
        group: "View",
        title: assistantOpen ? "Hide assistant" : "Show assistant",
        icon: SparklesIcon,
      },
      { id: "plugin-install", group: "Plugins", title: "Install plugin…", icon: SparklesIcon },
      { id: "search", group: "Navigation", title: "Focus search", icon: Search01Icon },
    ],
    [reasoning, assistantOpen],
  );

  const runCommand = useCallback(
    (id: string) => {
      switch (id) {
        case "open":
          openFile();
          break;
        case "save":
          save();
          break;
        case "undo":
          undo();
          break;
        case "redo":
          redo();
          break;
        case "reasoner":
          toggleReasoner();
          break;
        case "tree":
          setView("tree");
          break;
        case "graph":
          setView("graph");
          break;
        case "assistant":
          setAssistantOpen((open) => !open);
          break;
        case "plugin-install": {
          void openDialog({
            title: "Install plugin",
            description: "Provide the plugin JAR URL and its pinned SHA-256 digest.",
            fields: [
              { key: "url", label: "Plugin jar URL", placeholder: "https://example.com/plugin.jar", required: true },
              { key: "sha256", label: "SHA-256 of the jar (pinned)", placeholder: "64-char hex digest", required: true },
            ],
            confirmLabel: "Install",
          }).then((res) => {
            if (!res) {
              return;
            }
            const url = res.url.trim();
            const sha256 = res.sha256.trim();
            if (!url || !sha256) {
              return;
            }
            api
              .installPlugin(url, sha256)
              .then((result) => {
                setError(null);
                setNotice(`Installed ${result.installed} (${result.plugins} plugins loaded)`);
              })
              .catch((e: Error) => setError(e.message));
          });
          break;
        }
        case "search":
          document.querySelector<HTMLInputElement>("input.search")?.focus();
          break;
        default:
          break;
      }
    },
    [openDialog, openFile, redo, save, toggleReasoner, undo],
  );

  const roots = useMemo(() => children["root"] ?? [], [children]);

  return (
    <AppShell9
      sidebarOpen={sidebarOpen}
      onSidebarOpenChange={setSidebarOpen}
      sidebarWidth="w-14"
      sidebar={
        <AppSidebar2
          brand="O"
          items={[
            { label: "Ontology", icon: LayoutTemplateIcon, current: true, title: "Ontology" },
            { label: "Search", icon: Search01Icon, title: "Search hierarchy" },
            { label: "Connectors", icon: FlowConnectionIcon, title: "Connectors" },
            { label: "Assistant", icon: SparklesIcon, title: "Assistant" },
          ]}
          onNavigate={(label) => {
            if (label === "Ontology") {
              setView("tree");
              setConnectorsOpen(false);
              return;
            }
            if (label === "Search") {
              document.getElementById("hierarchy-filter")?.focus();
              return;
            }
            if (label === "Connectors") {
              setConnectorsOpen(true);
              return;
            }
            if (label === "Assistant") {
              setAssistantOpen(true);
            }
          }}
        />
      }
    >
    <div className="app">
      <header className="bar">
        <button onClick={openFile}>Open ontology…</button>
        {!sidebarOpen && (
          <button
            className="sidebar-toggle"
            onClick={() => setSidebarOpen(true)}
            title="Show sidebar"
            aria-label="Show sidebar"
          >
            ☰
          </button>
        )}
        <div className="segmented">
          <button className={view === "tree" ? "active" : ""} onClick={() => setView("tree")}>
            Tree
          </button>
          <button className={view === "graph" ? "active" : ""} onClick={() => setView("graph")}>
            Graph
          </button>
        </div>
        {view === "graph" && (
          <select
            className="depth"
            aria-label="Graph depth"
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
          aria-label="Search entities"
          placeholder="Search classes, properties, individuals"
          value={query}
          onChange={(event) => runSearch(event.target.value)}
        />
        {dirty && (
          <span className="unsaved-indicator" title="Unsaved changes">
            Unsaved
          </span>
        )}
        {ontology && (
          <span className="meta">
            {ontology.axiomCount.toLocaleString()} axioms · {ontology.entityCount} entities
          </span>
        )}
        <button
          type="button"
          className={assistantOpen ? "active-toggle" : ""}
          onClick={() => setAssistantOpen((open) => !open)}
          aria-pressed={assistantOpen}
          title={assistantOpen ? "Hide assistant" : "Show assistant"}
        >
          Assist
        </button>
        <CommandMenu1 commands={commands} onRun={runCommand} defaultOpen={false} compact />
      </header>

      {ontology && (
        <div className="branch-bar">
          <span className="muted">Branch</span>
          <select
            className="depth"
            aria-label="Active branch"
            value={activeBranch}
            onChange={(event) => switchBranch(event.target.value)}
          >
            {branches.map((branch) => (
              <option key={branch.name} value={branch.name}>
                {branch.name} ({branch.axioms})
              </option>
            ))}
          </select>
          <button onClick={newBranch}>New branch</button>
          {activeBranch !== "main" && (
            <>
              <button onClick={showDiff}>Diff</button>
              <button onClick={promote}>Promote</button>
            </>
          )}
          {activeBranch !== "main" && (
            <span className="muted">editing a proposal; main is untouched</span>
          )}
        </div>
      )}

      {diff && (
        <div className="branch-diff">
          <div className="preview-head">
            {diff.branch} vs main · +{diff.added.length} / -{diff.removed.length} asserted ·{" "}
            +{diff.inferredAdded.length} / -{diff.inferredRemoved.length} inferred
            <button className="remove" aria-label="Close branch diff" onClick={() => setDiff(null)}>
              ×
            </button>
          </div>
          <div className="diff-columns">
            <DiffList title="Added" items={diff.added} />
            <DiffList title="Removed" items={diff.removed} />
            <DiffList title="Inferred added" items={diff.inferredAdded} />
            <DiffList title="Inferred removed" items={diff.inferredRemoved} />
          </div>
        </div>
      )}

      <main className="workbench">
        <OntologyIdePage
          selectedIri={selected}
          onEdited={onEdited}
          searchValue={query}
          onSearchChange={runSearch}
          hierarchy={
            <>
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
              <aside className="tree" role="tree" aria-label="Class hierarchy">
                {treeLoading && roots.length === 0 && <div className="empty">Loading classes…</div>}
                {!treeLoading && roots.length === 0 && ontology && (
                  <div className="empty">No classes in ontology</div>
                )}
                {!treeLoading && roots.length === 0 && !ontology && (
                  <div className="empty empty-cta">
                    <p>Open an ontology to browse classes.</p>
                    <button type="button" onClick={openFile}>
                      Open ontology…
                    </button>
                  </div>
                )}
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
            </>
          }
          editor={
            view === "graph" ? (
              <div className="canvas">
                <ErrorBoundary name="Graph" onReset={() => setVersion((v) => v + 1)}>
                  <Suspense fallback={<div className="empty">Loading graph…</div>}>
                    <GraphView
                      depth={depth}
                      version={version}
                      inferred={reasoning}
                      selected={selected}
                      onSelect={select}
                      onError={setError}
                    />
                  </Suspense>
                </ErrorBoundary>
              </div>
            ) : undefined
          }
          inspector={
            entityLoading && !entity ? (
              <p className="empty">Loading entity…</p>
            ) : entity ? (
              <ErrorBoundary key={entity.iri + version} name="Entity details">
                <EntityPanel
                  key={entity.iri + version}
                  entity={entity}
                  onEdited={onEdited}
                  onSelect={select}
                  onError={setError}
                />
              </ErrorBoundary>
            ) : undefined
          }
          connectorsOpen={connectorsOpen}
          onConnectorsOpenChange={setConnectorsOpen}
          assistant={
            assistantOpen ? (
              <div className="assistant">
                <AssistantPanel entityIri={selected} entityLabel={entity?.label ?? null} />
              </div>
            ) : undefined
          }
        />
      </main>

      {!ontology && !error && (
        <div className="status" role="status">
          <span>No ontology open</span>
        </div>
      )}
      {notice && (
        <div className="notice" role="status">
          <span>{notice}</span>
          <button className="banner-dismiss" aria-label="Dismiss notice" onClick={() => setNotice(null)}>
            ×
          </button>
        </div>
      )}
      {error && (
        <div className="error" role="alert">
          <span>{error}</span>
          <button className="banner-dismiss" aria-label="Dismiss error" onClick={() => setError(null)}>
            ×
          </button>
        </div>
      )}
      <ModalDialog
        open={dialogState !== null}
        title={dialogState?.title ?? ""}
        description={dialogState?.description}
        fields={dialogState?.fields}
        confirmLabel={dialogState?.confirmLabel}
        destructive={dialogState?.destructive}
        onConfirm={(values) => {
          const resolve = dialogState?.resolve;
          setDialogState(null);
          resolve?.(values);
        }}
        onCancel={() => {
          const resolve = dialogState?.resolve;
          setDialogState(null);
          resolve?.(null);
        }}
      />
    </div>
    </AppShell9>
  );
}

function DiffList({ title, items }: { title: string; items: string[] }) {
  return (
    <div className="diff-col">
      <h4>{title}</h4>
      {items.length === 0 && <div className="muted">none</div>}
      {items.slice(0, 40).map((item) => (
        <div key={item} className="axiom">
          {item}
        </div>
      ))}
      {items.length > 40 && <div className="muted">+{items.length - 40} more</div>}
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

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect(node.iri);
    } else if (event.key === "ArrowRight" && node.hasChildren && !isOpen) {
      event.preventDefault();
      onToggle(node);
    } else if (event.key === "ArrowLeft" && node.hasChildren && isOpen) {
      event.preventDefault();
      onToggle(node);
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const tree = event.currentTarget.closest('[role="tree"]');
      if (!tree) {
        return;
      }
      const items = Array.from(tree.querySelectorAll<HTMLElement>('[role="treeitem"]'));
      const idx = items.indexOf(event.currentTarget);
      const nextIdx = event.key === "ArrowDown" ? idx + 1 : idx - 1;
      if (nextIdx >= 0 && nextIdx < items.length) {
        items[nextIdx].focus();
      }
    }
  };

  return (
    <div>
      <div
        role="treeitem"
        aria-selected={selected === node.iri}
        aria-expanded={node.hasChildren ? isOpen : undefined}
        aria-level={depth + 1}
        tabIndex={0}
        className={`row${selected === node.iri ? " active" : ""}`}
        style={{ paddingLeft: 8 + depth * 14 }}
        onClick={() => onSelect(node.iri)}
        onKeyDown={handleKeyDown}
      >
        <button
          type="button"
          tabIndex={-1}
          aria-label={
            node.hasChildren ? (isOpen ? `Collapse ${node.label}` : `Expand ${node.label}`) : undefined
          }
          aria-hidden={!node.hasChildren}
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
          <TreeItem key={child.iri} {...props} node={child} depth={depth + 1} />
        ))}
    </div>
  );
}
