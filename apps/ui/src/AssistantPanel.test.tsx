import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AssistantPanel, { visibleAssistantText } from "./AssistantPanel";
import { api } from "./api";

vi.mock("./api", () => ({
  api: {
    assistant: vi.fn(),
  },
}));

describe("AssistantPanel", () => {
  beforeEach(() => {
    vi.mocked(api.assistant).mockReset();
  });

  it("does not mention ANTHROPIC_API_KEY before a question is sent", () => {
    render(<AssistantPanel entityIri={null} entityLabel={null} />);
    expect(screen.getByRole("log", { name: "Assistant conversation" }).textContent ?? "").not.toContain(
      "ANTHROPIC_API_KEY",
    );
  });

  it("shows the OpenRouter key when an error still names ANTHROPIC_API_KEY", async () => {
    vi.mocked(api.assistant).mockRejectedValue(
      new Error("Set ANTHROPIC_API_KEY in the environment to use the assistant"),
    );
    render(<AssistantPanel entityIri={null} entityLabel={null} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Ask assistant" }), {
      target: { value: "What is Pizza?" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByText("Set OPENROUTER_API_KEY in the environment to use the assistant")).toBeInTheDocument();
    expect(document.body.textContent ?? "").not.toContain("ANTHROPIC_API_KEY");
  });

  it("rewrites only the Anthropic env name", () => {
    expect(visibleAssistantText("Set ANTHROPIC_API_KEY in the environment")).toBe(
      "Set OPENROUTER_API_KEY in the environment",
    );
  });
});
