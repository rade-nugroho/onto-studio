# Onto Studio engine. Build stage has a JDK and Maven. Runtime is distroless JRE 21.
# Digests looked up 2026-11-16 (multi-arch image indexes):
#   registry-1.docker.io/library/maven:3.9.11-eclipse-temurin-21-alpine
#   gcr.io/distroless/java21-debian12:nonroot
# The runtime stage has no shell and no package manager. The shaded jar is the
# only file copied forward. Launch and Healthcheck are added to that jar.

FROM maven:3.9.11-eclipse-temurin-21-alpine@sha256:922927df2c662cdd47ddb116443d6bec4696cfae3de1a0ddac8fcc7b87ce61ae AS build

WORKDIR /src
COPY services/engine/pom.xml pom.xml
COPY services/engine/src/main src/main
COPY services/engine/docker/Launch.java /docker/Launch.java
COPY services/engine/docker/Healthcheck.java /docker/Healthcheck.java

RUN mvn -B -Dmaven.test.skip=true package \
    && javac --release 21 -cp /src/target/engine.jar -d /tmp/engine-docker \
        /docker/Launch.java /docker/Healthcheck.java \
    && jar uf /src/target/engine.jar -C /tmp/engine-docker Launch.class -C /tmp/engine-docker Healthcheck.class \
    && rm -rf /tmp/engine-docker

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d

LABEL org.opencontainers.image.source="https://github.com/rade-nugroho/protege" \
      org.opencontainers.image.title="onto-studio-engine" \
      org.opencontainers.image.description="Onto Studio ontology engine"

COPY --from=build --chown=65532:65532 /src/target/engine.jar /app/engine.jar

WORKDIR /app
USER 65532:65532

ENV ENGINE_PORT=7717 \
    HOME=/home/nonroot \
    JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp"

EXPOSE 7717

HEALTHCHECK --interval=30s --timeout=15s --start-period=90s --retries=3 \
    CMD ["/usr/bin/java", "-Xmx64m", "-cp", "/app/engine.jar", "Healthcheck"]

ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/engine.jar", "Launch"]
