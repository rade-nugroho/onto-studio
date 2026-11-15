import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  type Edge,
  type Node,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { api, isAbortError, type GraphSlice } from "./api";
import { layoutNodes } from "./layout";

type GraphViewProps = {
  depth: number;
  version: number;
  inferred: boolean;
  selected: string | null;
  onSelect: (iri: string) => void;
  onError: (message: string) => void;
};

function edgesOf(graph: GraphSlice): Edge[] {
  return graph.edges.map((edge) => ({
    id: `${edge.source}->${edge.target}`,
    source: edge.source,
    target: edge.target,
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
    style: { stroke: "#9aa3b0", strokeWidth: 1.2 },
  }));
}

function Canvas({ depth, version, inferred, selected, onSelect, onError }: GraphViewProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    setLoading(true);
    api
      .graph(undefined, depth, inferred, controller.signal)
      .then((graph) => {
        if (cancelled) {
          return;
        }
        setNodes(layoutNodes(graph));
        setEdges(edgesOf(graph));
      })
      .catch((error: unknown) => {
        if (cancelled || isAbortError(error)) {
          return;
        }
        onError(error instanceof Error ? error.message : String(error));
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [depth, inferred, version, onError, setEdges, setNodes]);

  const styledNodes = useMemo(
    () =>
      nodes.map((node) =>
        node.id === selected
          ? { ...node, style: { ...node.style, border: "2px solid #2f6fed", background: "#e8f0fe" } }
          : node,
      ),
    [nodes, selected],
  );

  const handleClick = useCallback(
    (_: unknown, node: Node) => onSelect(node.id),
    [onSelect],
  );

  return (
    <div className="graph-wrap">
      {loading && (
        <div className="graph-loading" role="status" aria-live="polite">
          Loading graph…
        </div>
      )}
      <ReactFlow
        nodes={styledNodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleClick}
        fitView
        fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
        minZoom={0.2}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={18} color="#e6e9ee" />
        <Controls showInteractive={false} />
        <MiniMap pannable zoomable />
      </ReactFlow>
    </div>
  );
}

export default function GraphView(props: GraphViewProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}
