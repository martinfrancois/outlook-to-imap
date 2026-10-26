---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent, "Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# Runs as a shaded jar and as a container image

## Context and Problem Statement

[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) asked for
Docker and Podman "as the only deployment modes" and said not to add or document a standalone host
installation or a systemd service. The owner, when asking for the implementation, added that it
must run either as a fat shaded jar or in Docker. This record states that deviation from the issue.

How is the service packaged and shipped, so that it runs on a machine with Java but no container
runtime as well as in Docker or Podman, without two builds to keep in step?

## Decision Drivers

* The owner's instruction: a runnable fat jar and a container image.
* One artifact to test: what the jar does, the image must do too.
* The same configuration file in both, so the README documents one setup.
* The container must run as a non-root user and stop cleanly on `docker stop`.
* The image should run on common home-server hardware, including ARM boards.

## Considered Options

* One shaded jar, shipped on its own and as the content of a multi-arch image
* Container image only, as the issue asked

## Decision Outcome

Chosen option: "One shaded jar, shipped on its own and as the content of a multi-arch image",
because the owner asked for both, and the image is that same jar on a JRE, so the jar costs no
second build and no second set of tests.

The details:

* `./mvnw package` writes `target/outlook-to-imap.jar` with the maven-shade-plugin. The
  `ServicesResourceTransformer` merges `META-INF/services`, through which Flyway finds its database
  support and JDBC finds its drivers. The manifest sets `Multi-Release: true` and
  `Enable-Native-Access: ALL-UNNAMED` for sqlite-jdbc's native library. Licence and notice files of
  every dependency are kept.
* The `Dockerfile` builds that jar with the Maven wrapper on `eclipse-temurin:25-jdk` and runs it on
  `eclipse-temurin:25-jre`, both pinned by digest. The image runs as user `outlook-to-imap` with uid
  10001, home and volume `/data` with mode 700, and `-XX:MaxRAMPercentage=75`.
* The `ENTRYPOINT` is in exec form, so Java is process 1 and `docker stop` reaches the shutdown
  hook. curl is installed because the JRE image has none and the `HEALTHCHECK` calls `/health` on
  port 8080 with it.
* The only difference between the two at run time is the default state directory: `data` next to
  the configuration file for the jar, `/data` in the image, set by `OUTLOOK_TO_IMAP_STATE_DIRECTORY`.
  The image mounts the configuration read-only, which is why the state directory can't simply sit
  next to it.
* Releases attach `outlook-to-imap-<version>.jar` to the GitHub Release and push the image to
  `ghcr.io/martinfrancois/outlook-to-imap` for `linux/amd64` and `linux/arm64`, tagged with the full
  version, major and minor, major alone, and `latest`. `examples/compose.yaml` uses the major tag
  `1`. No systemd unit or host installer was added.

### Consequences

* Good, because the jar runs anywhere with Java 21 or newer, and the image needs no Java on the host.
* Good, because CI tests the jar on every Java LTS from 21 up and then smoke-tests the image, so both
  ship from tested code.
* Good, because arm64 users, such as Raspberry Pi or ARM VPS owners, get the same image.
  sqlite-jdbc carries the native library for each architecture, so the jar is identical on both.
* Bad, because the shaded jar is about 100 MB, most of it the Graph SDK
  ([ADR 0005](0005-graph-sdk-despite-its-size.md)).
* Bad, because files mounted into the container must be readable by uid 10001. The README tells
  users to run `sudo chown -R 10001` or, with rootless Podman, `podman unshare chown -R 10001`.
* Neutral, because Podman must build with `--format docker` to keep the `HEALTHCHECK`.

### Confirmation

`scripts/smoke-image.sh docker` (or `podman`) builds the image and checks that it starts, answers
`/health`, reports a missing login and stops cleanly; the CI job `image` runs it on every pull
request. `ConfigLoaderTest.theEnvironmentCanSetTheDefaultStateDirectory` checks the state directory
variable. The release workflow checks that the jar's `Implementation-Version` equals the release
version before it uploads the jar.

## Pros and Cons of the Options

### One shaded jar, shipped on its own and as the content of a multi-arch image

Maven builds one jar with every dependency inside. The image copies that jar onto a JRE. Both are
published with the same version number.

* Good, because there is one artifact to build and test.
* Good, because people with Java and no container runtime can run the service.
* Bad, because the shade configuration must keep service files, licences and the native-access
  grant working, and a wrong transformer breaks Flyway or JDBC only in the shaded jar.

### Container image only, as the issue asked

Publish the image and document only Docker and Podman.

* Good, because the README would cover one way to run it.
* Bad, because the owner asked for a jar as well.
* Bad, because it excludes machines with Java but no container runtime, although the jar exists
  inside the image anyway.

## More Information

Measured on 2026-10-26: the release asset `outlook-to-imap-1.0.0.jar` is 100,491,016 bytes. The
first image smoke run showed `uid=10001(outlook-to-imap)`, `/data` with mode 700, and a stop in
0.62 seconds. The agent checked that the shaded jar still loads Flyway's PostgreSQL support through
the merged service files.

The build stage first used a `maven` image; it moved to `./mvnw` on `eclipse-temurin:25-jdk` so the
image builds with the same Maven as CI, see
[ADR 0012](0012-releases-with-release-please-after-a-live-check.md). `useradd` first had `--system`
as well as an explicit uid; `--system` was dropped because with a fixed uid it only produced a
warning. The first review round on
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5) listed
arm64 as scope the issue did not ask for; it was kept. The uid 10001 ownership steps were added to
the README after the live check, in
[outlook-to-imap pull request #18](https://github.com/martinfrancois/outlook-to-imap/pull/18).

Which Java versions the jar supports and CI tests is in
[ADR 0011](0011-java-21-minimum-and-every-lts-tested.md).
