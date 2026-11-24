# syntax=docker/dockerfile:1.7
# -----------------------------------------------------------------------------
# Syncra HIMS — API Gateway image
#   Stage 1 builds a layered Spring Boot jar with Maven (no local JDK needed).
#   Stage 2 runs on a minimal JRE as an unprivileged user.
# Tests run in CI:  mvn -B verify   (skipped here to keep image builds fast)
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=layertools -jar target/app.jar extract --destination target/layers

FROM eclipse-temurin:21-jre-alpine AS runtime
LABEL org.opencontainers.image.title="syncra-api-gateway" \
      org.opencontainers.image.vendor="Syncra Technologies"

RUN addgroup -S syncra && adduser -S -G syncra -H -s /sbin/nologin syncra
WORKDIR /app

# Least-frequently-changing layers first for cache efficiency
COPY --from=build --chown=syncra:syncra /workspace/target/layers/dependencies/ ./
COPY --from=build --chown=syncra:syncra /workspace/target/layers/spring-boot-loader/ ./
COPY --from=build --chown=syncra:syncra /workspace/target/layers/snapshot-dependencies/ ./
COPY --from=build --chown=syncra:syncra /workspace/target/layers/application/ ./

USER syncra
EXPOSE 8080 9090

# JVM flags come from JAVA_TOOL_OPTIONS (set in docker-compose.yml)
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
