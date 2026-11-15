import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import ModalDialog from "./ModalDialog";

describe("ModalDialog", () => {
  it("renders nothing when closed", () => {
    const { container } = render(
      <ModalDialog open={false} title="Closed" onConfirm={vi.fn()} onCancel={vi.fn()} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("submits field values and enforces required fields", () => {
    const onConfirm = vi.fn();
    render(
      <ModalDialog
        open
        title="Install plugin"
        fields={[
          { key: "url", label: "Plugin jar URL", required: true },
          { key: "sha256", label: "SHA-256", required: true },
        ]}
        confirmLabel="Install"
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    );

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");

    const submit = screen.getByRole("button", { name: "Install" });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Plugin jar URL"), {
      target: { value: "https://example.com/plugin.jar" },
    });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText("SHA-256"), {
      target: { value: "abc123" },
    });
    expect(submit).not.toBeDisabled();

    fireEvent.click(submit);
    expect(onConfirm).toHaveBeenCalledWith({
      url: "https://example.com/plugin.jar",
      sha256: "abc123",
    });
  });

  it("cancels on Escape key and Cancel button", () => {
    const onCancel = vi.fn();
    render(
      <ModalDialog
        open
        title="Discard changes?"
        description="Unsaved edits will be lost."
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalledTimes(2);
  });
});
