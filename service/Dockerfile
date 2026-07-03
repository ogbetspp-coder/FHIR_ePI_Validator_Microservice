# Build context is the REPO ROOT (docker build -f service/Dockerfile .) so the image can seal
# tools/packages.lock.json (audit manifest source) alongside the vendored IG packages.
# No network is needed at runtime; only Maven Central is touched at build time.

# ---- Stage 1: build -------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency layer first for build caching
COPY service/pom.xml service/pom.xml
RUN mvn -B -q -f service/pom.xml dependency:go-offline

COPY tools/packages.lock.json tools/packages.lock.json
COPY service/src service/src
# .git is intentionally NOT copied; the build manifest falls back to buildId=unknown here.
# CI stamps the image tag with the commit SHA instead.
RUN mvn -B -q -f service/pom.xml package -DskipTests \
    && java -Djarmode=tools -jar service/target/epi-validator-*.jar extract --layers --destination /workspace/extracted

# ---- Stage 2: runtime -----------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# curl for the container healthcheck only
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home epi
USER 10001
WORKDIR /app

# Layer order: least -> most volatile for image layer caching
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# Memory (measured, one IG chain + terminology): steady working set ~800 MB heap, ~90 MB
# metaspace. In a 2 GiB container, MaxRAMPercentage=60 caps heap at ~1.23 GB (~440 MB GC
# headroom) and keeps resident set ~1.5 GB — ~530 MB margin under the limit. ExitOnOOM turns
# any genuine exhaustion into a fast restart rather than a degraded, thrashing instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --start-period=120s --interval=10s --timeout=3s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
