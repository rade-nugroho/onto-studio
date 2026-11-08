#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
RESOURCES="$ROOT/apps/desktop/src-tauri/resources"

echo "== engine =="
(cd "$ROOT/services/engine" && mvn -q -DskipTests package)

echo "== ui =="
(cd "$ROOT/apps/ui" && [ -d node_modules ] || npm install --no-audit --no-fund)
(cd "$ROOT/apps/ui" && npm run build)

echo "== bundled java runtime =="
rm -rf "$RESOURCES"
mkdir -p "$RESOURCES"
cp "$ROOT/services/engine/target/engine.jar" "$RESOURCES/engine.jar"
"$JAVA_HOME/bin/jlink" \
  --add-modules java.base,java.logging,java.naming,java.management,java.security.jgss,java.sql,java.xml,jdk.unsupported,jdk.crypto.ec \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$RESOURCES/jre"

echo "== tauri app =="
(cd "$ROOT/apps/desktop" && [ -d node_modules ] || npm install --no-audit --no-fund)
(cd "$ROOT/apps/desktop" && npm run build)

echo "Bundle: $ROOT/apps/desktop/src-tauri/target/release/bundle/macos/Onto Studio.app"
