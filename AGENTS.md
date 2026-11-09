# Agent notes

## Project

Onto Studio: internal ontology workbench. React UI + Tauri shell + Java engine.

- `apps/ui` — React 18, TypeScript, Vite, React Flow (`@xyflow/react`)
- `apps/desktop` — Tauri v2 (Rust), spawns the engine and passes port/token to the UI
- `services/engine` — Java 21, Javalin 6, OWL API 5.5.1, shaded into `target/engine.jar`

## Build and test

```bash
# engine
cd services/engine
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -DskipTests package

# ui
cd apps/ui
npm install --no-audit --no-fund
npm run build

# shell
cd apps/desktop/src-tauri
cargo build
```

Full dev run: `./scripts/dev.sh`.

## UI conventions

- Tailwind utilities in `apps/ui` size things in `rem`. Never set `font-size` on
  `:root`; set it on `.app` so the 16px rem scale (and therefore every Tailwind
  spacing/size class) stays intact.
- Tailwind Preflight is enabled; React Bits blocks depend on its button reset.

## Conventions

- Engine binds 127.0.0.1 only and requires `X-Engine-Token` on every request.
- The engine prints a single JSON handshake line on stdout and logs to stderr.
- `--watch-parent` makes the engine exit when its stdin pipe closes, so the shell
  never leaves an orphan JVM behind.
- UI calls the engine through `src/api.ts`; keep response shapes in sync with
  `services/engine` JSON.

## Git

- Commit messages: concise imperative style, no `Co-Authored-By` trailers.
- The old Protégé code is preserved in `legacy-protege` / `pre-ontostudio`.
