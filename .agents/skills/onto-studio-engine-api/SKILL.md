---
name: onto-studio-engine-api
description: >-
  Run and test the Onto Studio Java engine standalone, probe HTTP endpoints with
  X-Engine-Token, sync API types with apps/ui/src/api.ts, and test or install
  plugins. Use when working on engine endpoints, OWL API logic, or plugins.
---

# Onto Studio Engine API & Plugin Workflow

## Start the Engine Standalone

```bash
cd services/engine
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -DskipTests package
java -jar target/engine.jar --port 7717 --token dev
```

Every HTTP request must include `-H "X-Engine-Token: dev"` (bound to `127.0.0.1` only).

## Smoke-Test Endpoints

```bash
curl -H "X-Engine-Token: dev" http://127.0.0.1:7717/health
curl -H "X-Engine-Token: dev" -H "Content-Type: application/json" \
  -d '{"path":"/path/to/ontology.owl"}' http://127.0.0.1:7717/ontology/open
curl -H "X-Engine-Token: dev" http://127.0.0.1:7717/hierarchy
curl -H "X-Engine-Token: dev" "http://127.0.0.1:7717/graph?depth=2"
```

## Syncing Engine & UI

- Keep JSON response shapes in `services/engine` (`Main.java`, `Workspace.java`) synchronized with `apps/ui/src/api.ts`.
- Declared actions (`set-label`, `add-subclass`, `remove-subclass`, `set-domain`, `set-range`, `add-type`, `remove-type`) should be previewable and recorded in `.onto-studio/evidence.jsonl`.

## Plugins

- Reference implementation: `examples/plugin-sample`.
- External JARs in `~/.onto-studio/plugins/*.jar` run out-of-process (`PluginHost`, 10s timeout) with `read`-only permission.
- Install with SHA-256 pinning:
  ```bash
  curl -X POST http://127.0.0.1:7717/plugins/install \
    -H "X-Engine-Token: dev" -H "Content-Type: application/json" \
    -d '{"url":"https://example.com/plugin.jar","sha256":"<digest>"}'
  ```

## Assistant Environment Resolution

The assistant posts to OpenRouter `/chat/completions` as `deepseek/deepseek-v4.1-flash-1017`.
`RuntimeConfig` checks `studio.onto.openRouterApiKey`, then `EnvFile` searches for `OPENROUTER_API_KEY` in order:
1. Process environment
2. `.env.local` in CWD (or up to two parent directories)
3. `.env.local` next to `engine.jar`
4. `~/.onto-studio.env` (for the packaged app)
