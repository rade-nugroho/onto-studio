import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import { ClassExpressionEditor } from "./ClassExpressionEditor";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      previewSubclassExpression: vi.fn().mockResolvedValue({
        action: "add-subclass-expression",
        summary: ["add: some hasTopping MeatTopping"],
        changes: ["add: some hasTopping MeatTopping"],
      }),
      commitSubclassExpression: vi.fn(),
      previewEquivalentClass: vi.fn(),
      commitEquivalentClass: vi.fn(),
    },
  };
});

const subject = "http://example.org/IceCream";
const property = "http://example.org/hasTopping";
const filler = "http://example.org/MeatTopping";

describe("ClassExpressionEditor", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("builds a some expression and calls preview", async () => {
    render(<ClassExpressionEditor iri={subject} />);

    fireEvent.click(screen.getByRole("radio", { name: "Some" }));
    fireEvent.change(screen.getByLabelText(/Property IRI/), { target: { value: property } });
    fireEvent.change(screen.getByLabelText(/Filler class IRI/), { target: { value: filler } });

    const preview = screen.getByRole("button", { name: "Preview" });
    expect(preview).toBeEnabled();
    fireEvent.click(preview);

    await waitFor(() =>
      expect(api.previewSubclassExpression).toHaveBeenCalledWith(subject, {
        kind: "some",
        property,
        filler: { kind: "class", iri: filler },
      }),
    );
    expect(await screen.findByText("add: some hasTopping MeatTopping")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Commit" })).toBeEnabled();
    expect(api.commitSubclassExpression).not.toHaveBeenCalled();
  });
});
