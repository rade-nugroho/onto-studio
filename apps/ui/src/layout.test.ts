import { describe, expect, it } from "vitest";
import { COLUMNS_PER_ROW, NODE_WIDTH, ROW_GAP, layoutNodes } from "./layout";
import type { GraphSlice } from "./api";

function node(iri: string, depth: number) {
  return { iri, label: iri, depth };
}

describe("layoutNodes", () => {
  it("places deeper nodes below shallower ones", () => {
    const graph: GraphSlice = {
      nodes: [node("a", 0), node("b", 1)],
      edges: [{ source: "a", target: "b" }],
    };
    const [first, second] = layoutNodes(graph);
    expect(second.position.y).toBeGreaterThan(first.position.y);
    expect(second.position.y - first.position.y).toBe(ROW_GAP);
  });

  it("centers a layer horizontally around zero", () => {
    const graph: GraphSlice = {
      nodes: [node("a", 0), node("b", 0), node("c", 0)],
      edges: [],
    };
    const positions = layoutNodes(graph).map((item) => item.position.x);
    expect(positions[0]).toBe(-(NODE_WIDTH + 24));
    expect(positions[1]).toBe(0);
    expect(positions[2]).toBe(NODE_WIDTH + 24);
  });

  it("wraps a wide layer into rows instead of one long line", () => {
    const nodes = Array.from({ length: COLUMNS_PER_ROW + 2 }, (_, index) => node(`n${index}`, 0));
    const laidOut = layoutNodes({ nodes, edges: [] });
    const rows = new Set(laidOut.map((item) => item.position.y));
    expect(rows.size).toBe(2);
    expect(laidOut[COLUMNS_PER_ROW].position.y).toBe(ROW_GAP);
  });

  it("keeps each node addressable by its IRI", () => {
    const laidOut = layoutNodes({ nodes: [node("urn:x", 0)], edges: [] });
    expect(laidOut[0].id).toBe("urn:x");
  });
});
