#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ENGINE="${ENGINE:-../../services/engine/target/engine.jar}"
rm -rf out sample-plugin.jar
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
"$JAVA_HOME/bin/javac" --release 21 -cp "$ENGINE" -d out $(find src -name '*.java')
cp -R META-INF out/
jar cf sample-plugin.jar -C out .
mkdir -p "$HOME/.onto-studio/plugins"
cp sample-plugin.jar "$HOME/.onto-studio/plugins/"
echo "Installed sample-plugin.jar into ~/.onto-studio/plugins"
