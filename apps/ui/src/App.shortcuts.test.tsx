import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App, { shouldIgnoreGlobalShortcut } from "./App";
import { api } from "./api";

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      hierarchy: vi.fn().mockResolvedValue({ children: [] }),
      open: vi.fn().mockResolvedValue({
        iri: "urn:test",
        path: "/tmp/test.owl",
        axiomCount: 10,
        entityCount: 5,
      }),
      branches: vi.fn().mockResolvedValue({ branches: [{ name: "main", axioms: 10, active: true }] }),
      save: vi.fn().mockResolvedValue({
        dirty: false,
        canUndo: true,
        canRedo: false,
        path: "/tmp/test.owl",
      }),
      undo: vi.fn().mockResolvedValue({ dirty: true, canUndo: false, canRedo: true }),
      redo: vi.fn().mockResolvedValue({ dirty: true, canUndo: true, canRedo: false }),
    },
  };
});

describe("shouldIgnoreGlobalShortcut", () => {
  it("ignores shortcuts when target is INPUT, TEXTAREA, SELECT, or contentEditable", () => {
    for (const tag of ["input", "textarea", "select"]) {
      const el = document.createElement(tag);
      document.body.appendChild(el);
      const event = new KeyboardEvent("keydown", { key: "z", metaKey: true, bubbles: true });
      Object.defineProperty(event, "target", { value: el });
      expect(shouldIgnoreGlobalShortcut(event)).toBe(true);
      el.remove();
    }

    const editable = document.createElement("div");
    Object.defineProperty(editable, "isContentEditable", { value: true });
    document.body.appendChild(editable);
    const editableEvent = new KeyboardEvent("keydown", { key: "z", metaKey: true, bubbles: true });
    Object.defineProperty(editableEvent, "target", { value: editable });
    expect(shouldIgnoreGlobalShortcut(editableEvent)).toBe(true);
    editable.remove();
  });

  it("ignores shortcuts when an open [role='dialog'] exists", () => {
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    document.body.appendChild(dialog);

    const event = new KeyboardEvent("keydown", { key: "s", metaKey: true, bubbles: true });
    Object.defineProperty(event, "target", { value: document.body });
    expect(shouldIgnoreGlobalShortcut(event)).toBe(true);

    dialog.remove();
  });

  it("allows shortcuts when focus is outside editable elements and dialogs", () => {
    const button = document.createElement("button");
    document.body.appendChild(button);
    const event = new KeyboardEvent("keydown", { key: "s", metaKey: true, bubbles: true });
    Object.defineProperty(event, "target", { value: button });
    expect(shouldIgnoreGlobalShortcut(event)).toBe(false);
    button.remove();
  });
});

describe("App keyboard shortcuts", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("wires Cmd+O to openFile dialog and ignores Cmd+Z inside search input", async () => {
    render(<App />);

    await waitFor(() => expect(api.hierarchy).toHaveBeenCalled());

    const searchInput = screen.getByLabelText("Search entities");
    fireEvent.keyDown(searchInput, { key: "z", metaKey: true, bubbles: true });
    expect(api.undo).not.toHaveBeenCalled();

    fireEvent.keyDown(window, { key: "o", metaKey: true });
    const pathInput = await screen.findByLabelText("Ontology file path");
    fireEvent.change(pathInput, { target: { value: "/tmp/test.owl" } });
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    await waitFor(() => expect(api.open).toHaveBeenCalledWith("/tmp/test.owl"));
  });
});
