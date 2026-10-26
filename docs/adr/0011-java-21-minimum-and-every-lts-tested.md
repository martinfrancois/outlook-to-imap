---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository]
---

# Java 21 minimum, and CI tests every LTS from 21 up

## Context and Problem Statement

junkEmailMover supported Java 8. The new service needs a minimum Java version for the jar, a set of
versions CI tests, and a way to learn about new Java releases. A new Java LTS release ships every
two years. A test matrix written into the workflow by hand misses it until someone edits the file.

Which Java versions does the jar support, which does CI test, and how does the project hear about a
new LTS?

## Decision Drivers

* The owner set Java 21 as the minimum and dropped Java 8.
* A new LTS should be tested from the day it ships, without a workflow change.
* Non-LTS releases get about six months of updates, too short for a service meant to run unattended.
* Raising the minimum affects everyone who runs the jar, so a person must decide it.
* The owner asked for the same newer-LTS check the owner's other Java repositories have, using
  endoflife.date.

## Considered Options

* Minimum 21; CI reads the current LTS list from endoflife.date at run time; Renovate proposes a new
  minimum for approval
* A fixed matrix of Java versions in the workflow
* Test every Java release, LTS or not

## Decision Outcome

Chosen option: "Minimum 21; CI reads the current LTS list from endoflife.date at run time; Renovate
proposes a new minimum for approval", because it tests a new LTS on its release day with no change
to the repository, and keeps the decision to raise the minimum with a person.

The details:

* `pom.xml` sets `maven.compiler.release` to 21.
* The `lts` job in `.github/workflows/ci.yml` fetches
  `https://endoflife.date/api/eclipse-temurin.json`, keeps the LTS cycles from 21 upward with the
  `jq` filter `select((tonumber? // 0) >= 21)` and sorts them. When the site gives no usable
  answer, it warns and falls back to the committed list `["21","25"]`. The floor of 21 is written in
  both places, because endoflife.date keeps listing old LTS cycles after their support ends: on
  2026-10-26 it listed 25, 21, 17, 11 and 8. A pull request that raises `maven.compiler.release`
  must change the filter and the fallback list in the same pull request.
* The `test` job runs `./mvnw -B verify` once per version, with `fail-fast: false` so every
  version reports on its own.
* In `renovate.json`, a custom manager named `java-minimum` reads `maven.compiler.release` with the
  `endoflife-date` datasource. One package rule allows only LTS versions for it and for `java-jdk`,
  the JDK the release workflow sets up, whose versions look like `25.0.5+8.0.LTS`. Another holds the
  `java-minimum` pull request on the Dependency Dashboard until a person approves it. The image rule
  allows only LTS tags ending in `-jdk` or `-jre`. The repository's rule replaces the
  `javaLTSVersions` workaround in `config:recommended`, whose list ends at 25
  ([outlook-to-imap pull request #28](https://github.com/martinfrancois/outlook-to-imap/pull/28)).
* The image runs on the newest LTS JRE, Java 25 on 2026-10-26
  ([ADR 0002](0002-jar-and-container.md)).

### Consequences

* Good, because a new LTS joins the matrix on the day endoflife.date lists it.
* Good, because a failure on one Java version doesn't hide the result of the others.
* Bad, because Renovate's `java-minimum` pull request changes only `pom.xml`. The person who
  approves it has to raise the floor in `ci.yml` in the same pull request. Otherwise the matrix
  keeps a leg for a Java version that can't build the jar any more, and that leg fails.
* Bad, because CI depends on endoflife.date being reachable. The fallback list keeps CI running when
  it isn't, and the warning says so in the log.
* Neutral, because the required check stays one job named `build`, whatever the matrix contains
  ([ADR 0012](0012-releases-with-release-please-after-a-live-check.md)).

### Confirmation

Every CI run logs a line "Testing Java [...]" from the `lts` job. On 2026-10-26 the list was
`["21","25"]`. `pom.xml` has `<maven.compiler.release>21</maven.compiler.release>`, and the `lts`
job in `.github/workflows/ci.yml` starts at the same version, both in its filter
`select((tonumber? // 0) >= 21)` and in its fallback list `["21","25"]`. The Dependency
Dashboard, [outlook-to-imap issue #11](https://github.com/martinfrancois/outlook-to-imap/issues/11),
lists "java-minimum to v25" under the updates waiting for approval.

## Pros and Cons of the Options

### Minimum 21; CI reads the current LTS list from endoflife.date at run time; Renovate proposes a new minimum for approval

A small job asks endoflife.date for the current Eclipse Temurin LTS cycles and passes them to the
test matrix. Renovate watches the minimum separately and asks before raising it.

* Good, because nobody has to remember to add a new LTS.
* Good, because raising the minimum is a visible, approved change.
* Bad, because the matrix grows by one leg every two years and never shrinks by itself. Only
  raising the floor in the workflow, together with `maven.compiler.release`, drops an old version.

### A fixed matrix of Java versions in the workflow

List `21` and `25` in `ci.yml` and edit the list by hand.

* Good, because CI needs no network call before the tests.
* Bad, because a new LTS goes untested until someone edits the workflow.

### Test every Java release, LTS or not

Include every current Java feature release in the matrix.

* Good, because problems with a coming LTS would show up earlier.
* Bad, because a non-LTS release is supported for about six months, so the project would chase
  versions that people running a service rarely use.

## More Information

The owner set Java 21 as the minimum on 2026-10-26, and in the same session asked for the
newer-LTS check from the owner's other Java repositories. The agent took the endoflife.date query
with its fallback list from the CI of siusdata-to-postgres-adapter. The README section
"Development" describes the matrix to contributors.
