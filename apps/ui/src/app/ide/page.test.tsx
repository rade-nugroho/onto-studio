import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import AppSidebar2 from "../../components/blocks/app-sidebar-2";
import OntologyIdePage from "./page";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      connectorConnections: vi.fn().mockRejectedValue(new Error("GET /connectors/connections not found")),
    },
  };
});

function ConnectorsHarness() {
  const [open, setOpen] = useState(true);
  return <OntologyIdePage connectorsOpen={open} onConnectorsOpenChange={setOpen} />;
}

describe("OntologyIdePage layout", () => {
  it("shows separate editor tabs, the inspector empty line, and a primary nav", () => {
    render(
      <>
        <AppSidebar2 />
        <OntologyIdePage />
      </>,
    );

    const primary = screen.getByRole("navigation", { name: "Primary" });
    expect(within(primary).getAllByRole("button").length).toBeGreaterThan(0);
    expect(within(primary).getByRole("button", { name: "Ontology" })).toHaveAttribute("title", "Ontology");
    expect(within(primary).getByRole("button", { name: "Search" })).toHaveAttribute("title", "Search hierarchy");
    expect(within(primary).getByRole("button", { name: "Connectors" })).toHaveAttribute("title", "Connectors");
    expect(within(primary).getByRole("button", { name: "Assistant" })).toHaveAttribute("title", "Assistant");

    expect(screen.getByRole("tab", { name: "Class expression" })).toHaveTextContent("Class expression");
    expect(screen.getByRole("tab", { name: "Characteristics" })).toHaveTextContent("Characteristics");
    expect(screen.getByRole("tab", { name: "Assertions" })).toHaveTextContent("Assertions");

    expect(screen.queryByRole("navigation", { name: "Dock" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Assistant" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Connectors" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Assistant" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Connectors" })).toHaveLength(1);
    expect(screen.queryByText(/The assistant is hidden/i)).not.toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "Inspector" })).toBeInTheDocument();
    expect(screen.getByText("Select a class to see its axioms.")).toBeInTheDocument();
    expect(screen.queryByRole("complementary", { name: "Assistant" })).not.toBeInTheDocument();
  });

  it("places the assistant in the right column", () => {
    render(<OntologyIdePage assistant="Assistant ready" />);

    const column = screen.getByRole("complementary", { name: "Assistant" });
    expect(within(column).getByText("Assistant ready")).toBeInTheDocument();
    expect(within(column).getByText("Select a class to see its axioms.")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Dock" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Assistant" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Connectors" })).not.toBeInTheDocument();
    expect(screen.queryByText(/The assistant is hidden/i)).not.toBeInTheDocument();
  });

  it("replaces the editor when connectors are open and restores it on close", async () => {
    render(<ConnectorsHarness />);

    expect(screen.queryByRole("tab", { name: "Class expression" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Characteristics" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Assertions" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Connectors" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Yours" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Discover" })).toBeInTheDocument();
    expect(await screen.findByText("GET /connectors/connections not found")).toBeInTheDocument();
    expect(api.connectorConnections).toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Back to editor" }));

    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "Class expression" })).toBeInTheDocument();
    });
    expect(screen.queryByRole("heading", { name: "Connectors" })).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Dock" })).not.toBeInTheDocument();
  });
});
