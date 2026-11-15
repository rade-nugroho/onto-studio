import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EntityPanel from "./EntityPanel";
import { api, type EntityInfo } from "./api";

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      plugins: vi.fn().mockResolvedValue({ plugins: [] }),
      audit: vi.fn().mockResolvedValue({ rows: [] }),
      previewAction: vi.fn().mockResolvedValue({
        action: "set-label",
        summary: ["Rename Pizza"],
        changes: ["Label: Pizza -> Margherita"],
      }),
      commitAction: vi.fn(),
    },
  };
});

const sampleEntity: EntityInfo = {
  iri: "urn:pizza#Pizza",
  label: "Pizza",
  kind: "class",
  marking: "public",
  usage: 2,
  superClasses: [],
  subClasses: [],
  domains: [],
  ranges: [],
  types: [],
  annotations: [],
  axioms: ["SubClassOf: Food"],
};

describe("EntityPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("disables Commit while busy and prevents double-submit", async () => {
    type CommitResult = Awaited<ReturnType<typeof api.commitAction>>;
    let resolveCommit: ((value: CommitResult) => void) | undefined;
    vi.mocked(api.commitAction).mockImplementation(
      () =>
        new Promise<CommitResult>((resolve) => {
          resolveCommit = resolve;
        }),
    );

    const onEdited = vi.fn();
    render(
      <EntityPanel
        entity={sampleEntity}
        onEdited={onEdited}
        onSelect={vi.fn()}
        onError={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("New label"), { target: { value: "Margherita" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));

    const commitButton = await screen.findByRole("button", { name: "Commit" });
    fireEvent.click(commitButton);
    fireEvent.click(commitButton);

    expect(api.commitAction).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Committing…" })).toBeDisabled();

    resolveCommit?.({
      dirty: true,
      canUndo: true,
      canRedo: false,
      action: "set-label",
      summary: ["Rename Pizza"],
    });

    await waitFor(() => expect(onEdited).toHaveBeenCalledTimes(1));
  });
});
