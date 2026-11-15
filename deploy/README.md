# Engine delivery

The container is the Java engine only. The Tauri shell stays on the desktop.

Build from the repository root:

```bash
docker build -f services/engine/Dockerfile -t "onto-studio-engine:$(git rev-parse HEAD)" .
```

The image tag is the git commit sha. The runtime user is `65532:65532`. The base is the distroless JRE 21 image pinned in `services/engine/Dockerfile`. Port `7717` is above 1024, so the container adds no capabilities.

## Environment

Set `ENGINE_TOKEN` in the environment before starting a container. The image does not contain a token, and these files do not either. The process passes that value to the existing `--token` flag and still rejects requests that omit `X-Engine-Token`. The image healthcheck sends `X-Engine-Token` from `ENGINE_TOKEN`. When `KEYCLOAK_ISSUER` is set, `GET /health` stays anonymous, as it does outside Docker.

`ENGINE_BIND` chooses the listen address. Unset or blank stays `127.0.0.1`, which is what the desktop shell uses. Compose sets `ENGINE_BIND=0.0.0.0` inside the container so the published port and the healthcheck can connect. The host publish is `127.0.0.1:7717` only.

Put ontology files in the directory mounted at `/data` (`ONTO_STUDIO_DATA`, or `./data` for dev). The engine writes `.onto-studio/evidence.jsonl`, markings, and connector mappings beside the ontology. That directory is the writable volume. The root filesystem stays read-only. `/tmp` is a `noexec,nosuid` tmpfs for the JVM. `/home/nonroot` is a tmpfs for plugin files under the numeric user's home.

## Promotion

1. Dev runs after CI is green. The pull request job builds `onto-studio-engine:<git sha>` and does not push it.
2. Staging is a manual `workflow_dispatch` with the `staging` environment. Attach required reviewers to that GitHub environment before the first run.
3. Prod is a second manual `workflow_dispatch` with the `prod` environment and its own required reviewers.

The workflow does not deploy to a server and does not create cloud infrastructure. On staging or prod, if `ENGINE_REGISTRY`, `ENGINE_REGISTRY_USERNAME`, and `ENGINE_REGISTRY_PASSWORD` are all set, the job pushes `${ENGINE_REGISTRY}:<git sha>`. If any of them is missing, the job prints the local tag and stops. A pull request does not fail for a missing registry.

```bash
export ENGINE_TOKEN="..."
export ENGINE_IMAGE_TAG="$(git rev-parse HEAD)"
export ONTO_STUDIO_DATA="/path/to/ontologies"

docker compose -f compose.dev.yml up --build
docker compose -f compose.staging.yml up
docker compose -f compose.prod.yml up
```

Open an ontology at `/data/<file>.owl`.

## Production flags

`compose.prod.yml` sets `read_only`, `cap_drop: [ALL]` with nothing added back, `no-new-privileges:true`, `pids_limit: 256`, `mem_limit: 2g`, `cpus: "2.0"`, `user: "65532:65532"`, and a `/tmp` tmpfs with `noexec,nosuid`. Staging uses the same flags with `pids_limit: 128`, `mem_limit: 1g`, and `cpus: "1.0"`, and restarts `on-failure`. Neither file mounts the Docker socket or sets `privileged`.

Host daemon TLS, `/etc/docker/daemon.json`, auditd, content trust, and privileged bench runs are outside this change.
