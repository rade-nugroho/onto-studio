import type { Node } from "@xyflow/react";
import type { GraphSlice } from "./api";

export const NODE_WIDTH = 180;
export const NODE_HEIGHT = 38;
export const COLUMN_GAP = 24;
export const ROW_GAP = 90;
export const COLUMNS_PER_ROW = 8;

export function layoutNodes(graph: GraphSlice): Node[] {
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

