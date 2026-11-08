import { useCallback, useEffect, useMemo } from "react";
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
import { api, type GraphSlice } from "./api";
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

  useEffect(() => {
    let cancelled = false;
    api
      .graph(undefined, depth, inferred)
      .then((graph) => {
        if (cancelled) {
          return;
        }
        setNodes(layoutNodes(graph));
        setEdges(edgesOf(graph));
      })
      .catch((error: Error) => onError(error.message));
    return () => {
      cancelled = true;
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
  );
}

export default function GraphView(props: GraphViewProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}
