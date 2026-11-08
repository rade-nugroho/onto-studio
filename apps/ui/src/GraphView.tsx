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

type GraphViewProps = {
  depth: number;
  version: number;
  selected: string | null;
  onSelect: (iri: string) => void;
  onError: (message: string) => void;
};

const NODE_WIDTH = 180;
const NODE_HEIGHT = 38;
const COLUMN_GAP = 24;
const ROW_GAP = 90;
const COLUMNS_PER_ROW = 8;

function layout(graph: GraphSlice): Node[] {
  const byDepth = new Map<number, GraphSlice["nodes"]>();
  for (const node of graph.nodes) {
    const layer = byDepth.get(node.depth) ?? [];
    layer.push(node);
    byDepth.set(node.depth, layer);
  }
  const nodes: Node[] = [];
  let yCursor = 0;
  for (const [, layer] of [...byDepth.entries()].sort((a, b) => a[0] - b[0])) {
    const rows = Math.ceil(layer.length / COLUMNS_PER_ROW);
    layer.forEach((node, index) => {
      const row = Math.floor(index / COLUMNS_PER_ROW);
      const column = index % COLUMNS_PER_ROW;
      const columnsInRow = Math.min(COLUMNS_PER_ROW, layer.length - row * COLUMNS_PER_ROW);
      nodes.push({
        id: node.iri,
        position: {
          x: (column - (columnsInRow - 1) / 2) * (NODE_WIDTH + COLUMN_GAP),
          y: yCursor + row * ROW_GAP,
        },
        data: { label: node.label },
        style: {
          width: NODE_WIDTH,
          height: NODE_HEIGHT,
          borderRadius: 8,
          border: "1px solid #d7dbe2",
          background: "#ffffff",
          fontSize: 12,
          padding: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        },
      });
    });
    yCursor += rows * ROW_GAP;
  }
  return nodes;
}

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

function Canvas({ depth, version, selected, onSelect, onError }: GraphViewProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);

  useEffect(() => {
    let cancelled = false;
    api
      .graph(undefined, depth)
      .then((graph) => {
        if (cancelled) {
          return;
        }
        setNodes(layout(graph));
        setEdges(edgesOf(graph));
      })
      .catch((error: Error) => onError(error.message));
    return () => {
      cancelled = true;
    };
  }, [depth, version, onError, setEdges, setNodes]);

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
