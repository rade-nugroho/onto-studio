import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ErrorBoundary from "./ErrorBoundary";

function Bomb({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) {
    throw new Error("boom in render");
  }
  return <div>Healthy child</div>;
}

describe("ErrorBoundary", () => {
  it("renders fallback on error and resets when Retry is clicked", () => {
    const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const onReset = vi.fn();

    function Wrapper() {
      const [shouldThrow, setShouldThrow] = useState(true);
      return (
        <ErrorBoundary
          name="Graph"
          onReset={() => {
            onReset();
            setShouldThrow(false);
          }}
        >
          <Bomb shouldThrow={shouldThrow} />
        </ErrorBoundary>
      );
    }

    render(<Wrapper />);
    expect(screen.getByRole("alert")).toHaveTextContent("Graph failed to render");
    expect(screen.getByRole("alert")).toHaveTextContent("boom in render");

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(onReset).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Healthy child")).toBeInTheDocument();
    consoleSpy.mockRestore();
  });
});
