# Build context is the REPO ROOT (docker build -f service/Dockerfile .) so the image can seal
# tools/packages.lock.json alongside the vendored IG packages.
# No network is needed at runtime; only Maven Central is touched at build time.

# ---- Stage 1: build -------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency layer first for build caching
COPY service/pom.xml service/pom.xml
RUN mvn -B -q -f service/pom.xml dependency:go-offline

COPY tools/packages.lock.json tools/packages.lock.json
COPY service/src service/src
RUN mvn -B -q -f service/pom.xml package -DskipTests

# ---- Stage 2: runtime -----------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# curl for the container healthcheck only
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home epi
USER 10001
WORKDIR /app

# Run the Spring Boot fat jar directly. Its own manifest names the correct launcher for this
# Boot version, so there is no dependence on exploded-layer layout or classpath assembly. That
# layered-extraction approach is sensitive to the Docker builder and produced an image whose
# launcher class was missing, so Cloud Run crashed on start with ClassNotFoundException.
COPY --from=build /workspace/service/target/epi-validator-*.jar /app/app.jar

# Memory (measured, one IG chain + terminology): steady working set ~800 MB heap, ~90 MB
# metaspace. In a 2 GiB container, MaxRAMPercentage=60 caps heap at ~1.23 GB (~440 MB GC
# headroom) and keeps resident set ~1.5 GB, ~530 MB margin under the limit. ExitOnOOM turns
# any genuine exhaustion into a fast restart rather than a degraded, thrashing instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError"
# Run Actuator (health/liveness/readiness) on a SEPARATE port. Each validation is CPU/memory-bound
# and pins a worker thread for its duration, and the request pool is small (threads.max=4); putting
# probes on their own connector means a burst of slow validations on 8080 can never starve the
# liveness probe and trigger a false restart. The app API stays on 8080; probes are on 8081.
ENV MANAGEMENT_SERVER_PORT=8081
EXPOSE 8080 8081

HEALTHCHECK --start-period=120s --interval=10s --timeout=5s --retries=6 \
    CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
