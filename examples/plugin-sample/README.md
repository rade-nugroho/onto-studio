# Sample plugin

Adds an action `sample:deprecate` that marks an entity with `owl:deprecated`.

The plugin only reads the ontology and returns proposed changes; the engine
applies them. It declares the default permission set (`read`).

## Build

```bash
cd examples/plugin-sample
ENGINE=../../services/engine/target/engine.jar
"$(/usr/libexec/java_home -v 21)/bin/javac" --release 21 -cp "$ENGINE" -d out $(find src -name '*.java')
cp -R META-INF out/
jar cf sample-plugin.jar -C out .
mkdir -p ~/.onto-studio/plugins
cp sample-plugin.jar ~/.onto-studio/plugins/
```

Restart the engine (or the app) and check `GET /plugins`; the action appears in
`/action/preview` and `/action/commit` under the name `sample:deprecate`.
