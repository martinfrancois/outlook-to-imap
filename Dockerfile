# Builds the same shaded jar as `./mvnw package` and runs it on a Java 25 runtime.
FROM docker.io/library/eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
WORKDIR /src
# Without unzip the wrapper downloads Maven's .tar.gz instead of the .zip whose SHA-256
# maven-wrapper.properties holds, and stops at the checksum.
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
ENV OUTLOOK_TO_IMAP_CONFIG=/config/config.yaml \
    OUTLOOK_TO_IMAP_STATE_DIRECTORY=/data
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=1m --timeout=10s --start-period=2m --retries=3 \
    CMD curl -fsS -o /dev/null http://127.0.0.1:8080/health || exit 1
# Exec form makes java PID 1, so `docker stop` reaches the shutdown hook that ends a poll cleanly.
# A JVM out of memory exits, so the restart policy brings back a clean one.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/outlook-to-imap/outlook-to-imap.jar"]
CMD ["run"]
