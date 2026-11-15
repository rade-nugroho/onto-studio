#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_RES="$ROOT/apps/desktop/src-tauri/target/release/bundle/macos/Onto Studio.app/Contents/Resources/resources"
STAGE_RES="$ROOT/apps/desktop/src-tauri/resources"

RES_DIR="${1:-}"
if [ -z "$RES_DIR" ]; then
  if [ -d "$APP_RES" ]; then
    RES_DIR="$APP_RES"
  else
    RES_DIR="$STAGE_RES"
  fi
fi

JAVA_BIN="$RES_DIR/jre/bin/java"
ENGINE_JAR="$RES_DIR/engine.jar"

if [ ! -x "$JAVA_BIN" ]; then
  echo "Error: bundled java not found or not executable at $JAVA_BIN" >&2
  exit 1
fi
if [ ! -f "$ENGINE_JAR" ]; then
  echo "Error: bundled engine.jar not found at $ENGINE_JAR" >&2
  exit 1
fi

echo "== smoke test: $RES_DIR =="
python3 - "$JAVA_BIN" "$ENGINE_JAR" <<'PY'
import json
import subprocess
import sys
import urllib.request

java_bin, engine_jar = sys.argv[1], sys.argv[2]

proc = subprocess.Popen(
    [java_bin, "-jar", engine_jar, "--port", "0", "--watch-parent"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
)

try:
    line = proc.stdout.readline()
    if not line:
        stderr = proc.stderr.read()
        raise RuntimeError(f"Engine exited before handshake. stderr:\n{stderr}")

    handshake = json.loads(line.strip())
    port = int(handshake["port"])
    token = str(handshake["token"])
    if not (1 <= port <= 65535) or not token:
        raise RuntimeError(f"Invalid handshake: {handshake}")

    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/health",
        headers={"X-Engine-Token": token},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Unexpected /health status: {resp.status}")
        body = json.loads(resp.read().decode("utf-8"))
        if body.get("status") != "ok":
            raise RuntimeError(f"Unexpected /health body: {body}")

    proc.stdin.close()
    returncode = proc.wait(timeout=15)
    if returncode != 0:
        stderr = proc.stderr.read()
        raise RuntimeError(f"Engine exited with {returncode} on stdin EOF. stderr:\n{stderr}")
    print(f"Smoke test passed (port={port}, health=ok, clean EOF exit).")
finally:
    if proc.poll() is None:
        proc.kill()
        proc.wait()
PY
