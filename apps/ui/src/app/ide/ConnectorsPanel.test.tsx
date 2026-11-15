import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import { ConnectorsPanel } from "./ConnectorsPanel";

vi.mock("../../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      connectorConnections: vi.fn().mockResolvedValue({
        connections: [
          {
            service: "github",
            name: "default",
            authType: "oauth2",
            status: "connected",
          },
        ],
        gatewayAvailable: true,
      }),
      connectorApps: vi.fn().mockResolvedValue({
        apps: [{ id: "slack", name: "Slack", authType: "oauth2", locallyExecutable: true }],
        gatewayAvailable: true,
      }),
      connectorActions: vi.fn().mockResolvedValue({
        actions: [
          {
            id: "github.list_issues",
            description: "List issues",
            outputFields: ["id", "title"],
          },
        ],
        gatewayAvailable: true,
      }),
      previewConnectorMapping: vi.fn().mockResolvedValue({
        classIri: "http://example.org#Issue",
        stored: false,
        individuals: [
          {
            iri: "http://example.org#github-1",
            label: "1",
            kind: "individual",
            marking: "public",
            usage: 0,
            superClasses: [],
            subClasses: [],
            domains: [],
            ranges: [],
            types: [],
            annotations: [],
            axioms: ['1 : Issue'],
          },
        ],
      }),
      commitConnectorMapping: vi.fn(),
    },
  };
});

describe("ConnectorsPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("Discover search renders a provider row", async () => {
    render(<ConnectorsPanel />);
    fireEvent.click(screen.getByRole("radio", { name: "Discover" }));
    await waitFor(() => expect(api.connectorApps).toHaveBeenCalled());
    expect(await screen.findByText("Slack")).toBeInTheDocument();
  });

  it("calls Preview and not Commit until mapping is confirmed", async () => {
    render(<ConnectorsPanel />);
    await waitFor(() => expect(api.connectorConnections).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole("button", { name: /github/i }));
    fireEvent.click(await screen.findByRole("button", { name: /github\.list_issues/i }));
    fireEvent.change(screen.getByLabelText("Class IRI"), {
      target: { value: "http://example.org#Issue" },
    });
    fireEvent.change(screen.getByLabelText("ID output field"), { target: { value: "id" } });
    fireEvent.change(screen.getByLabelText("Field map"), {
      target: { value: "title=http://example.org#title" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));
    await waitFor(() => expect(api.previewConnectorMapping).toHaveBeenCalled());
    expect(api.commitConnectorMapping).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Commit mapping" }));
    await waitFor(() => expect(api.commitConnectorMapping).toHaveBeenCalled());
  });
});