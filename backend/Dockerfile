# syntax=docker/dockerfile:1.7
# =============================================================================
# Spring Boot backend: multi-stage build, OpenTelemetry Java agent, non-root.
# =============================================================================

# ---- 1) build ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -e dependency:go-offline

COPY src ./src
# maven.test.skip: don't compile/run tests while building the image (run `mvn test` in CI instead)
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -e package -Dmaven.test.skip=true

# ---- 2) runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

ARG OTEL_AGENT_VERSION=2.10.0

# curl: container health check
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system app && useradd --system --gid app --home-dir /app app

# OpenTelemetry Java agent: auto-instrumentation (HTTP server, JDBC/Hikari, Redis, JVM metrics...)
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar

WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

USER app
EXPOSE 2424

# 60% of the container limit for the heap leaves room for metaspace, threads and the agent.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:2424/actuator/health || exit 1

# Set OTEL_JAVAAGENT_ENABLED=false to run without the agent.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -javaagent:/opt/otel/opentelemetry-javaagent.jar -jar /app/app.jar"]
