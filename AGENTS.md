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
Full test suite (engine + UI + shell): `./scripts/test.sh`.
macOS app bundle with bundled JRE: `./scripts/package.sh`.

## UI conventions

- Tailwind utilities in `apps/ui` size things in `rem`. Never set `font-size` on
  `:root`; set it on `.app` so the 16px rem scale (and therefore every Tailwind
  spacing/size class) stays intact.
- Tailwind Preflight is enabled; React Bits blocks depend on its button reset.

## Conventions

- Engine binds 127.0.0.1 only. Routes require `X-Engine-Token` unless `KEYCLOAK_ISSUER` is set, in which case they require a Keycloak bearer token. `GET /health` and `GET /openapi.json` are anonymous in that mode. `GET /llms.txt` and `/docs/intro.md` and `/docs/example.md` are public.
- The engine prints a single JSON handshake line on stdout and logs to stderr.
- `--watch-parent` makes the engine exit when its stdin pipe closes, so the shell
  never leaves an orphan JVM behind.
- UI calls the engine through `src/api.ts`; keep response shapes in sync with
  `services/engine` JSON.
- All ontology mutations must flow through the engine so `.onto-studio/evidence.jsonl`
  records every edit; third-party plugins (`studio.onto.engine.plugin.Plugin`) only
  receive read-only context and propose changes.
- Restricted entities in `.onto-studio/markings.json` are omitted from exports unless
  `includeRestricted: true` is explicitly passed.
- Track new release/packaging/testing work items in GitHub Issues and mirror them in
  `ISSUES.md`.

## Git

- Commit messages: concise imperative style, no `Co-Authored-By` trailers.
- Default branch is `master`. There is no `main`; `origin/HEAD` is `origin/master`.
- Old Swing Protégé code is branch `legacy-protege` and tag `pre-ontostudio`, in the git worktree `/Users/macbook/protege-swing`.

## Learned User Preferences

- Keep the assistant on OpenRouter only. Do not restore Anthropic or add a second model provider.
- Do not clone OpenConnector, keycloak-adaptive-authn, Infisical, or Scalar into this repository.
- Workbench empty states name the next action. An ontology that is not open is a neutral status, and controls use visible names.

## Learned Workspace Facts

- The assistant calls OpenRouter chat completions with model `deepseek/deepseek-v4.1-flash-1017`. `POST /systemone/submit` forwards a System One body (`model`, `state`, `questions`) to OpenRouter. Both read `OPENROUTER_API_KEY` and must not log or persist it.
- When `KEYCLOAK_ISSUER` is unset, engine routes use `X-Engine-Token`. When it is set, they require a Keycloak bearer token checked for issuer, expiry, and audience (`KEYCLOAK_AUDIENCE`, default `onto-studio`). Adaptive checks stay in Keycloak; this engine does not embed that extension.
- OpenConnector is a sidecar (`OPEN_CONNECTOR_URL`). Mappings are stored in `.onto-studio/mapping.json`. Mapped `/search` and `/entity` reads return live rows and do not copy them into the OWL file. Write and destructive connector actions are not executable. Connector secrets stay in the environment or Infisical, never in mapping files, evidence, or logs.
- The OWL workbench is `apps/ui/src/app/ide` (class expressions, property characteristics, individual assertions, connectors). Pages under `apps/ui/src/app` follow `apps/ui/AGENTS.md`.
- Compiler target stays 21. If a Java 21 home is missing, use Temurin 25 at `/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`.
