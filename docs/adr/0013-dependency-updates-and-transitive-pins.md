---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26]
informed: [Future maintainers and agents working on this repository]
---

# Dependency updates and transitive pins

## Context and Problem Statement

The service ships every runtime dependency inside its jar ([ADR 0002](0002-jar-and-container.md)),
and the Graph SDK alone brings dozens of them ([ADR 0005](0005-graph-sdk-despite-its-size.md)).
Some of those transitive dependencies had known vulnerabilities in the version Maven picked. The
project needs a policy for how updates arrive, which ones merge on their own, and how versions the
project never declares get pinned.

On 2026-10-26, before the 1.0.0 release, an OSV query over the 65 runtime dependencies found that
the jar would ship jackson-core 2.20.1 (4 advisories), jackson-databind 2.20.1 (12) and
opentelemetry-api 1.60.1 (1, GHSA-rcgg-9c38-7xpx, fixed in 1.62.0). How should the project keep its
direct and transitive dependencies current and free of known vulnerabilities?

## Decision Drivers

* A fresh release can be broken or malicious; waiting a few days lets problems surface first.
* A security fix should not wait.
* Updates that rarely break, such as patches and digests, should not need a person.
* A transitive version should not depend on which test library happens to ask for a newer one.
* Each pinned version is one more thing to keep current by hand, unless a tool updates it.

## Considered Options

* Renovate with a 7-day wait, and transitive families pinned by importing their BOMs
* Leave transitive versions to Maven's dependency mediation
* Declare `jakarta.mail-api` next to its implementation

## Decision Outcome

Chosen option: "Renovate with a 7-day wait, and transitive families pinned by importing their
BOMs", because a BOM import pins every module of a family with one version that Renovate keeps
current, and the wait plus the security exception matches the owner's policy for public
repositories.

`renovate.json`:

* `minimumReleaseAge` is 7 days with `minimumReleaseAgeBehaviour` `timestamp-required`, so a
  release without a known date is not proposed.
* `vulnerabilityAlerts` sets `minimumReleaseAge` to null, so a security fix skips the wait.
* Minor, patch, pin and digest updates merge on their own after the checks pass. Major updates get
  the label "major update" and wait for a person.
* The Flyway modules form one group, because they must match `flyway-core` exactly. The
  Testcontainers modules form another, because they are released together.
* Java versions are LTS only, and raising the minimum Java version waits for approval on the
  Dependency Dashboard ([ADR 0011](0011-java-21-minimum-and-every-lts-tested.md)).

`pom.xml` imports `com.fasterxml.jackson:jackson-bom` and `io.opentelemetry:opentelemetry-bom` in
`dependencyManagement`. Jackson 2 is there only because the Graph SDK needs it at run time; the
project's own JSON and YAML code uses Jackson 3 (`tools.jackson`).

### Consequences

* Good, because after the BOM imports the OSV query over the 65 runtime dependencies found no
  advisories.
* Good, because Renovate updates the BOM versions like any other dependency.
* Bad, because every update arrives at least a week after its release, unless it fixes a known
  vulnerability.
* Bad, because the jar carries both Jackson 2, for the SDK, and Jackson 3, for the project's code.
* Neutral, because jackson-core 2 and 3 ship byte-identical `FastDoubleParser-ThirdParty-LICENSE`
  and `Schubfach-LICENSE` files, which the shade plugin appends into one.

### Confirmation

`renovate.json` holds the rules above; Renovate's Dependency Dashboard is
[outlook-to-imap issue #11](https://github.com/martinfrancois/outlook-to-imap/issues/11). In the
built jar, `META-INF/maven/com.fasterxml.jackson.core/jackson-databind/pom.properties` shows the
version from `jackson-bom`. `./mvnw dependency:tree -Dverbose` shows which libraries ask for
Jackson 2 and which version wins.

## Pros and Cons of the Options

### Renovate with a 7-day wait, and transitive families pinned by importing their BOMs

Renovate proposes every update after 7 days, merges the low-risk ones itself and leaves majors to a
person. Families of libraries the project doesn't use directly are pinned through a BOM import.

* Good, because one line pins every Jackson 2 or OpenTelemetry module the SDK brings in, including
  modules a later SDK version adds.
* Good, because the pins stay current without manual work.
* Bad, because a BOM import overrides what the SDK asked for, so an SDK that needs an older Jackson
  2 could break. The WireMock tests against the real SDK would show that.

### Leave transitive versions to Maven's dependency mediation

Declare only direct dependencies and let Maven pick the nearest version of everything else.

* Good, because `pom.xml` stays short.
* Bad, because Maven took jackson 2.20.1 from WireMock, a test dependency. Without WireMock it
  would take azure-core's 2.18.6, which is also affected. The shipped version depended on the test
  setup.

### Declare `jakarta.mail-api` next to its implementation

[junkEmailMover issue #48](https://github.com/martinfrancois/junkEmailMover/issues/48) asked to
declare `jakarta.mail-api` explicitly instead of relying on Angus Mail to bring it in.

* Good, because the API the code compiles against would be visible in `pom.xml`.
* Bad, because angus-mail implements exactly one API version, and declaring the API separately adds
  a second version to keep in step by hand. The Graph SDK's own artifacts, such as Kiota, OkHttp
  and graph-core, stay undeclared for the same reason.

## More Information

The vulnerability audit was triggered by the owner asking, before the release, to check every open
issue. `mvn dependency:tree -Dverbose` showed that Jackson 2 is needed at run time by azure-core
1.58.0 (asks for 2.18.6), jwks-rsa 0.24.1 (2.21.2) and jjwt-jackson 0.13.0 (2.12.7.1), all through
microsoft-graph-core 3.6.6. OpenTelemetry comes with Kiota 1.9.2. The BOM versions chosen were
`jackson-bom` 2.22.4 and `opentelemetry-bom` 1.67.0, both older than the 7-day wait at the time.
The change is
[outlook-to-imap pull request #21](https://github.com/martinfrancois/outlook-to-imap/pull/21), merged
before 1.0.0. Vulnerability alerts were turned on for the repository the same day.

The Renovate configuration copies the owner's baseline for public repositories, taken from
junkEmailMover, and was validated with Renovate 44.181.7 in strict mode. The owner enrolled the
repository in Renovate on 2026-10-26
([outlook-to-imap issue #1](https://github.com/martinfrancois/outlook-to-imap/issues/1)).

The reason for leaving the API undeclared comes from the comment that closed
[junkEmailMover issue #48](https://github.com/martinfrancois/junkEmailMover/issues/48) on
2026-10-26. The project uses Jackson 3 for its configuration file and the `/health` body; the
session recorded no further reason for picking 3 over 2.
