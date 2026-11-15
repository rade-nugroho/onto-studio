# Onto Studio

Local OWL workbench. The React UI is `apps/ui`, the Tauri v2 shell is `apps/desktop`, and the Java 21 engine is `services/engine` (Javalin and OWL API, shaded to `target/engine.jar`). The shell starts the engine and passes the port and session token to the UI.

Repository: https://github.com/rade-nugroho/protege

Default branch: `master`.

## Layout

```
apps/ui/            React, TypeScript, Vite
apps/desktop/       Tauri v2 shell (Rust)
services/engine/    Java engine
scripts/            dev, test, and package helpers
```

## Requirements

- JDK 21
- Node.js and npm
- Rust toolchain (`cargo`)

## Build

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

## Test

`./scripts/test.sh` runs the engine tests, the UI tests, and the shell tests.

## Development

`./scripts/dev.sh` builds the engine jar, starts the Vite dev server, then runs the Tauri shell.

`./scripts/package.sh` builds a macOS app bundle with a bundled Java runtime.

## Engine

The engine listens on `127.0.0.1`. Routes require the `X-Engine-Token` header unless `KEYCLOAK_ISSUER` is set. When that variable is set, routes require a Keycloak bearer token checked for issuer, expiry, and audience (`KEYCLOAK_AUDIENCE`, default `onto-studio`). In that mode, `GET /health` and `GET /openapi.json` are anonymous. `GET /llms.txt`, `GET /docs/intro.md`, and `GET /docs/example.md` are public.

Run the engine by itself:

```bash
cd services/engine
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -DskipTests package
java -jar target/engine.jar --port 7717 --token dev
```

```bash
curl -H "X-Engine-Token: dev" http://127.0.0.1:7717/health
```

The OpenAPI document is `GET /openapi.json`.

## Assistant

The assistant calls OpenRouter chat completions (`POST https://openrouter.ai/api/v1/chat/completions`) with model `deepseek/deepseek-v4.1-flash-1017`. Set `OPENROUTER_API_KEY` in the environment. The engine also reads `.env.local` in the working directory or up to two levels above it, `.env.local` next to the engine jar, and `~/.onto-studio.env`. The key is not logged. `POST /systemone/submit` forwards a System One body (`model`, `state`, `questions`) to the same endpoint.

## Connectors

OpenConnector is an external sidecar (`OPEN_CONNECTOR_URL`). This repository does not include it. Mappings live in `.onto-studio/mapping.json`. Mapped `/search` and `/entity` reads return live rows and leave the OWL file unchanged. The engine does not execute connector writes or destructive actions. Connector secrets stay in the environment or Infisical.

## Markings and evidence

Markings live in `.onto-studio/markings.json` next to the ontology. Export omits axioms that mention restricted entities unless the request sets `includeRestricted` to true.

Every ontology edit is recorded in `.onto-studio/evidence.jsonl`. Plugins implement `studio.onto.engine.plugin.Plugin`, receive a read-only context, and propose changes. The engine applies those changes. See `examples/plugin-sample`.

## License

Onto Studio code is licensed under the GNU Affero General Public License version 3 only ([AGPL-3.0-only](LICENSE)). If you run this program, or a modified version of it, for users over a network, you must offer those users the corresponding source.

## Security

Report vulnerabilities through GitHub private security advisories. See [SECURITY.md](SECURITY.md). Operational rules are in [POLICY.md](POLICY.md).

## History

The previous Protégé Desktop code is on the `legacy-protege` branch and the `pre-ontostudio` tag.
