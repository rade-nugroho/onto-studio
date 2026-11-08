## Git

- Commit messages: concise imperative style, no `Co-Authored-By` trailers.
- Branch from `main`.

## Project

- Protege Desktop: a Java/Swing ontology editor on OSGi (Apache Felix), not a web app.
- Root `pom.xml` modules: `protege-launcher`, `protege-common`, `protege-editor-core`, `protege-editor-owl`, `protege-desktop`.
- Upstream is https://github.com/protegeproject/protege (https://protege.stanford.edu). This clone's origin is https://github.com/rade-nugroho/protege.git.

## Build

- JDK that can compile Java 11. The compiler plugin release is 11. GitHub Actions runs tests on JDK 11 and JDK 21.
- Apache Maven 3.6.3 or newer.
- From the repository root, build and test with `mvn --batch-mode clean verify`.

## Boundaries

- Do not change license terms or Code of Conduct obligations.
- Vulnerability reports: follow `SECURITY.md`.
