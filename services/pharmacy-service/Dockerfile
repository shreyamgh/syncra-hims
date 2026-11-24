# syntax=docker/dockerfile:1.7
# -----------------------------------------------------------------------------
# Syncra HIMS — Pharmacy Service image. Build context: repository root
# (the shared libs/hims-common library is installed first).
# Tests run in CI:  make test-pharmacy
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY libs/hims-common/pom.xml libs/hims-common/pom.xml
COPY libs/hims-common/src libs/hims-common/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f libs/hims-common/pom.xml -DskipTests install

COPY services/pharmacy-service/pom.xml services/pharmacy-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f services/pharmacy-service/pom.xml dependency:go-offline
COPY services/pharmacy-service/src services/pharmacy-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f services/pharmacy-service/pom.xml -DskipTests package \
 && java -Djarmode=layertools -jar services/pharmacy-service/target/app.jar extract --destination /layers

FROM eclipse-temurin:21-jre-alpine AS runtime
LABEL org.opencontainers.image.title="syncra-pharmacy-service" \
      org.opencontainers.image.vendor="Syncra Technologies"

RUN addgroup -S syncra && adduser -S -G syncra -H -s /sbin/nologin syncra
WORKDIR /app

COPY --from=build --chown=syncra:syncra /layers/dependencies/ ./
COPY --from=build --chown=syncra:syncra /layers/spring-boot-loader/ ./
COPY --from=build --chown=syncra:syncra /layers/snapshot-dependencies/ ./
COPY --from=build --chown=syncra:syncra /layers/application/ ./

USER syncra
EXPOSE 8084 9094

# JVM flags come from JAVA_TOOL_OPTIONS (set in docker-compose.yml)
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
