# Onto Studio

Internal, keyboard-first ontology workbench for ontology engineers.
React UI in a Tauri shell, with a local Java engine that owns OWL API.

## Layout

```
apps/ui/            React + TypeScript + Vite front end
apps/desktop/       Tauri v2 shell (Rust), spawns the engine
services/engine/    Java 21 engine: Javalin + OWL API
scripts/            dev helpers
```

## Requirements

- JDK 21 (`brew install openjdk@21`)
- Node 20+ and npm
- Rust toolchain (cargo)

## Run in development

```bash
./scripts/dev.sh
```

The script builds the engine jar, starts Vite, then runs the Tauri shell.
The shell spawns `services/engine/target/engine.jar` on an ephemeral port and
passes the port and session token to the UI through the `engine_info` command.

## Markings

Markings live in `.onto-studio/markings.json` next to the ontology. `Export`
writes a copy without axioms that mention restricted entities; pass
`includeRestricted: true` to override. The UI warns how many axioms were skipped.

## Evidence

Every edit is recorded in `.onto-studio/evidence.jsonl` next to the ontology:
timestamp, action (add/remove/import), source (manual/undo/redo/file), author
(`ONTO_STUDIO_AUTHOR` or the OS user), axiom text, entities, and confidence.
Click an axiom in the UI to see its evidence inline.

## Assistant configuration

The assistant reads credentials from, in order: the process environment, a
`.env.local` in the working directory (or up to two levels above), `.env.local`
next to the engine jar, and `~/.onto-studio.env` for the packaged app:

```
ANTHROPIC_API_KEY=...
ANTHROPIC_MODEL=claude-haiku-5-5        # optional
ANTHROPIC_API_URL=https://api.anthropic.com   # optional, full /v1/messages URL also accepted
```

## Run the engine alone

```bash
cd services/engine
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -DskipTests package
java -jar target/engine.jar --port 7717 --token dev
```

Then, with `X-Engine-Token: dev`:

```bash
curl -H "X-Engine-Token: dev" http://127.0.0.1:7717/health
curl -H "X-Engine-Token: dev" -H "Content-Type: application/json" \
     -d '{"path":"/path/to/pizza.owl"}' http://127.0.0.1:7717/ontology/open
curl -H "X-Engine-Token: dev" http://127.0.0.1:7717/hierarchy
curl -H "X-Engine-Token: dev" "http://127.0.0.1:7717/graph?depth=2"
```

## API (v0)

| Endpoint | Purpose |
|---|---|
| `GET /health` | Liveness and version |
| `POST /ontology/open` | Load an ontology from a file path |
| `GET /hierarchy?parent=` | Direct subclasses of a class (or top-level classes) |
| `GET /entity?iri=` | Label, annotations, and axioms of a class |
| `GET /search?q=` | Search classes, properties, and individuals |
| `GET /graph?depth=` | Class hierarchy slice as nodes and edges |
| `GET /evidence?iri=` | Where an entity's axioms came from (add/remove/import rows) |
| `GET /why?iri=&axiom=` | Asserts or infers one axiom and returns its evidence or support |
| `GET /marking?iri=` | Classification marking for an entity |
| `POST /marking/set` | Set `public`, `internal`, or `restricted` |
| `POST /export` | Write a copy; restricted axioms are omitted unless `includeRestricted` |
| `GET /branch/list` | Branches, their axiom counts, and which is active |
| `GET /branch/diff?name=` | Asserted and inferred diff against main |
| `POST /branch/create`, `/branch/switch`, `/branch/promote` | Proposal workflow |
| `POST /axiom/subclass` | Add a `SubClassOf` axiom |
| `POST /axiom/subclass/remove` | Remove a `SubClassOf` axiom |
| `POST /ontology/save` | Save to the opened file or a new path |
| `POST /assistant` | Ask the ontology assistant (Claude Managed Agents) |

All endpoints require the `X-Engine-Token` header.

## UI

- **Tree** view: lazy class hierarchy.
- **Graph** view: React Flow canvas of the hierarchy, depth 1-3, click a node to open it.
- Click any class to see annotations and axioms (DL syntax) in the right panel.

## Status

P0-P4 done: open, browse, search, graph, editing (superclass + free Manchester
axioms, properties/individuals), undo/redo, structural reasoner, command
palette (Cmd+K), keyboard shortcuts (Cmd+Z/S/1/2), assistant grounded in the
ontology (Claude Managed Agents with ontology tools, needs `ANTHROPIC_API_KEY`), and a packaged macOS app
with a bundled Java runtime (`scripts/package.sh`).

## History

The previous Protégé Desktop code lives in the `legacy-protege` branch and the
`pre-ontostudio` tag.
