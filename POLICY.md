# Policy

## License

Onto Studio code is AGPL-3.0-only. The full text is [LICENSE](LICENSE). A network deployment must offer the corresponding source to the users who interact with it.

## Secrets

Secrets stay out of git. Put them in the environment or Infisical. Do not print tokens in logs.

## Restricted entities

Restricted ontology entities in `.onto-studio/markings.json` stay out of exports unless `includeRestricted` is explicitly set.

## Connectors

The engine does not execute connector writes or destructive actions.

## Branches

`master` is the default branch.
