import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, isAbortError } from "./api";

describe("api error and timeout handling", () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it("extracts JSON error messages on !response.ok", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: "Ontology not found" }), {
        status: 404,
        statusText: "Not Found",
      }),
    );

    await expect(api.hierarchy()).rejects.toThrow("Ontology not found");
  });

  it("preserves HTTP status and body when !response.ok returns non-JSON", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(
      new Response("<html>502 Bad Gateway</html>", {
        status: 502,
        statusText: "Bad Gateway",
      }),
    );

    await expect(api.hierarchy()).rejects.toThrow("HTTP 502: <html>502 Bad Gateway</html>");
  });

  it("throws a clear error when fetch rejects with a network error", async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(api.hierarchy()).rejects.toThrow("Engine is not reachable.");
  });

  it("propagates AbortError when cancelled via AbortSignal", async () => {
    const controller = new AbortController();
    globalThis.fetch = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => {
      return new Promise<Response>((_resolve, reject) => {
        if (init?.signal?.aborted) {
          reject(new DOMException("Aborted", "AbortError"));
          return;
        }
        init?.signal?.addEventListener("abort", () => {
          reject(new DOMException("Aborted", "AbortError"));
        });
      });
    });

    const promise = api.hierarchy(undefined, false, controller.signal);
    controller.abort();

    try {
      await promise;
      expect.unreachable("Expected promise to reject");
    } catch (error) {
      expect(isAbortError(error)).toBe(true);
    }
  });
});
