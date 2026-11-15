---
name: onto-studio-test-and-package
description: >-
  Run the Onto Studio test suites (Java 21 engine, Vitest UI, Rust/Tauri shell)
  and build the macOS .app bundle with jlink. Use when verifying changes across
  runtimes or debugging build/packaging issues.
---

# Onto Studio Test and Package

## Prerequisites

- JDK 21 (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`)
- Node 20+ and npm
- Rust toolchain (`cargo`)

## Run All Tests

```bash
./scripts/test.sh
```

This runs:
1. `services/engine`: `mvn -q test`
2. `apps/ui`: `npm test` (`vitest run`)
3. `apps/desktop/src-tauri`: `cargo test`

## Run Targeted Checks

- **Engine only**:
  ```bash
  cd services/engine && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
  ```
- **UI typecheck + build**:
  ```bash
  cd apps/ui && npm run build
  ```
- **UI unit tests**:
  ```bash
  cd apps/ui && npm test
  ```
- **Tauri shell**:
  ```bash
  cd apps/desktop/src-tauri && cargo test
  ```

## Package macOS App

```bash
./scripts/package.sh
```

Notes:
- Rebuilds `services/engine/target/engine.jar` and `apps/ui/dist`, then runs `jlink` into `apps/desktop/src-tauri/resources/jre`.
- If adding Java stdlib dependencies (for example HTTP or XML APIs), verify the required module is included in the `--add-modules` list in `scripts/package.sh`.
- Output bundle: `apps/desktop/src-tauri/target/release/bundle/macos/Onto Studio.app`.
