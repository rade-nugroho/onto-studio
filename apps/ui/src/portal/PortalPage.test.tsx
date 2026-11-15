import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PortalPage, portalEngineTarget } from "./PortalPage";

vi.mock("@scalar/api-reference-react", () => ({
  ApiReferenceReact: ({
    configuration,
  }: {
    configuration: { content?: { info?: { title?: string } } };
  }) => <section data-testid="scalar-reference">{configuration.content?.info?.title}</section>,
}));

vi.mock("@scalar/api-reference-react/style.css", () => ({}));

describe("portalEngineTarget", () => {
  it("points Scalar at the engine OpenAPI document", () => {
    expect(portalEngineTarget("")).toEqual({
      url: "http://127.0.0.1:7717/openapi.json",
      token: "dev",
    });
    expect(portalEngineTarget("?engine=http://127.0.0.1:8899/&token=local")).toEqual({
      url: "http://127.0.0.1:8899/openapi.json",
      token: "local",
    });
  });
});

describe("PortalPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the introduction, Scalar reference, and example case", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        openapi: "3.0.3",
        info: { title: "Onto Studio Engine", version: "0.1.0" },
      }),
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<PortalPage />);

    expect(screen.getByRole("heading", { name: "Introduction" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "API reference" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Example case" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "keycloak-adaptive-authn" })).toHaveAttribute(
      "href",
      "https://github.com/mabartos/keycloak-adaptive-authn",
    );
    const text = document.body.textContent ?? "";
    expect(text).toContain("device posture");
    expect(text).toContain("universal auth");
    expect(text).toContain("GET /api/v4/secrets/OPEN_CONNECTOR_TOKEN");
    expect(text).toContain("stored");
    expect(text).toContain("github.list_issues");
    expect(text).toContain("connectionName");
    expect(text).toContain("idField");
    expect(text).toContain("fieldMap");
    expect(text).toContain("http://example.org#github-1");
    expect(text).toContain("connectorSource");
    expect(await screen.findByTestId("scalar-reference")).toHaveTextContent("Onto Studio Engine");
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:7717/openapi.json",
      expect.objectContaining({
        headers: { "X-Engine-Token": "dev" },
      }),
    );
  });
});
