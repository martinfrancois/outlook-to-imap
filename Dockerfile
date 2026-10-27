# Builds the same shaded jar as `./mvnw package` and runs it on a Java 25 runtime.
FROM docker.io/library/eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
WORKDIR /src
# Without unzip the wrapper downloads Maven's .tar.gz instead of the .zip whose SHA-256 it checks, and stops, see docs/adr/0021-release-integrity-with-sha3-and-attestations.md
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
# The wrapper pins the Maven version, so the image builds with the same Maven as CI.
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src ./src
# CI runs the tests; the image build only packages.
RUN ./mvnw -B -q package -DskipTests -Djacoco.skip=true

FROM docker.io/library/eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
# curl runs the health check below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --user-group --home-dir /data --shell /usr/sbin/nologin outlook-to-imap \
    && mkdir -p /data /config \
    && chown outlook-to-imap:outlook-to-imap /data \
    && chmod 700 /data
COPY --from=build /src/target/outlook-to-imap.jar /opt/outlook-to-imap/outlook-to-imap.jar
USER outlook-to-imap
# Without the state directory variable the state would go next to the configuration, which is mounted read-only, see docs/adr/0002-jar-and-container.md
ENV OUTLOOK_TO_IMAP_CONFIG=/config/config.yaml \
    OUTLOOK_TO_IMAP_STATE_DIRECTORY=/data
VOLUME ["/data"]
EXPOSE 8080
# Calls 127.0.0.1, the default bind address of /health; a bind address that does not accept connections to 127.0.0.1, such as ::1, fails the check, see docs/adr/0020-verified-postgresql-tls-and-loopback-health-by-default.md
HEALTHCHECK --interval=1m --timeout=10s --start-period=2m --retries=3 \
    CMD curl -fsS -o /dev/null http://127.0.0.1:8080/health || exit 1
# Shell form would make a shell PID 1, and `docker stop` would never reach the shutdown hook, see docs/adr/0002-jar-and-container.md
# Without -XX:+ExitOnOutOfMemoryError a JVM out of memory keeps running instead of the restart policy starting a clean one, see docs/adr/0023-a-poll-that-dies-of-an-error-keeps-the-service-polling.md
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/outlook-to-imap/outlook-to-imap.jar"]
CMD ["run"]
