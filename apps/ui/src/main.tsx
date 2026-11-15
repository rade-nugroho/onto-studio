import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@astryxdesign/core/reset.css";
import "@astryxdesign/core/astryx.css";
import { Theme } from "@astryxdesign/core/theme";
import { themes, defaultThemeSlug } from "./astryx-themes";
import App from "./App";
import ErrorBoundary from "./ErrorBoundary";
import "./styles.css";

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Missing root element");
}

const portalRoute = window.location.pathname.replace(/\/+$/, "") || "/";
if (portalRoute === "/portal") {
  void import("./portal/PortalPage").then(({ PortalPage }) => {
    createRoot(rootElement).render(
      <StrictMode>
        <Theme theme={themes[defaultThemeSlug]}>
          <PortalPage />
        </Theme>
      </StrictMode>,
    );
  });
} else {
  createRoot(rootElement).render(
    <StrictMode>
      <Theme theme={themes[defaultThemeSlug]}>
        <ErrorBoundary name="Application">
          <App />
        </ErrorBoundary>
      </Theme>
    </StrictMode>,
  );
}
