---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26]
informed: [Future maintainers and agents working on this repository]
---

# Releases with release-please, after a live check

## Context and Problem Statement

junkEmailMover published nothing: CI built and tested, and people built the jar themselves. The new
service is meant to run unattended from a published jar or image
([ADR 0002](0002-jar-and-container.md)), so it needs version numbers people can pin, a way to
publish both artifacts with the same version, and a check that blocks a merge when any CI job
fails. The owner also wanted the first real release to be 1.0.0 and to work against real
Microsoft accounts, which no test in CI can reach.

How are releases made and published, which check guards `main`, and what must happen before the
first release?

## Decision Drivers

* People should pin a version, not a commit hash.
* The jar and the image of one release must carry the same version and come from the same tested
  commit.
* The required check must keep its name when the Java test matrix changes.
* Every build, local or CI, should use the same Maven version.
* Behavior against real Microsoft and IMAP servers can only be checked by hand, and that check must
  be possible for someone other than the person who wrote the code.

## Considered Options

* release-please releases: a GitHub Release with the jar and an image with the same version, merged
  after a live check
* Source only: people build the jar and the image themselves
* Push the image on every merge to `main`, tagged by commit

## Decision Outcome

Chosen option: "release-please releases: a GitHub Release with the jar and an image with the same
version, merged after a live check", because it gives the jar and the image one version number that
people can pin, and builds both from the tagged commit after its tests pass.

What comes with it:

* Commit messages follow Conventional Commits, which release-please reads to pick the version and
  write the changelog. commitlint checks every pull request (`.github/workflows/commitlint.yml`).
* `release-please-config.json` uses release type `maven`, initial version 1.0.0, tags with a `v`,
  and creates the release as a draft with `force-tag-creation`, so the tag exists while the release
  is still a draft.
* When a release is created, `.github/workflows/release-please.yml` checks out the tag, runs
  `./mvnw -B verify` again, attaches `outlook-to-imap-<version>.jar` and its `.sha256`, checks that
  the jar's `Implementation-Version` equals the version, pushes the multi-arch image, and only then
  publishes the draft.
* The ruleset on `main` requires one status check named `build`, plus linear history, no force
  push and no deletion. The `build` job in `ci.yml` depends on every other job and fails unless all
  of them passed. It runs with `if: always()`, because a skipped required check does not block a
  merge.
* A pull request that the Actions token opens or updates starts no `pull_request` workflows. So
  after release-please creates or updates its pull request, the release workflow starts `ci.yml` on
  the release branch with `gh workflow run`. Those check runs attach to the branch head and count as
  the pull request's checks.
* `./mvnw`, the Maven wrapper (`only-script`), runs every build: CI, the release build, the Docker
  build stage and local builds. Renovate updates the Maven version in
  `.mvn/wrapper/maven-wrapper.properties`.
* Before 1.0.0, a live check against two real Microsoft accounts and a real IMAP server had to pass.
  The check was written as a self-contained issue, so another person or agent could run it without
  the context of the session that wrote the code.

### Consequences

* Good, because every release is a tested commit with a jar, a checksum and an image of the same
  version.
* Good, because the `build` check keeps its name whatever Java versions the matrix holds
  ([ADR 0011](0011-java-21-minimum-and-every-lts-tested.md)).
* Bad, because shipping needs a release pull request to merge, a release workflow and a commitlint
  workflow to maintain.
* Bad, because release pull request updates currently wait for a manual approval of their
  `pull_request` runs, see
  [outlook-to-imap issue #14](https://github.com/martinfrancois/outlook-to-imap/issues/14).
* Neutral, because commits by people who don't follow Conventional Commits fail commitlint until
  their message is fixed.

### Confirmation

`gh release view v1.0.0 -R martinfrancois/outlook-to-imap` shows the jar and its checksum. The
`build` job in `.github/workflows/ci.yml` lists `needs: [lts, test, image]` and `if: always()`. The
release workflow has the step "Run CI on the release pull request". The Dockerfile, both workflows
and the README all build with `./mvnw`.

## Pros and Cons of the Options

### release-please releases: a GitHub Release with the jar and an image with the same version, merged after a live check

release-please keeps a release pull request open with the next version and changelog. Merging it
tags the commit; the release workflow then builds, verifies and publishes the jar and the image.

* Good, because the jar and the image share one version, and people pin `1` or `1.0.0`.
* Good, because the owner already used the same setup in other repositories, such as
  symphony-trello.
* Bad, because it needs commitlint, a release workflow and the extra CI dispatch.

### Source only: people build the jar and the image themselves

Nothing is published. The README tells people to run `./mvnw package` and `docker build`.

* Good, because there is nothing to release.
* Bad, because every user needs a JDK or a full Docker build, and nobody runs a known-good version.

### Push the image on every merge to `main`, tagged by commit

CI pushes the image tagged `main` and `sha-<commit>` after each merge, with no downloadable jar.

* Good, because every merge is available at once.
* Bad, because people would pin a commit hash, and there would be no versioned jar.

## More Information

The owner chose release-please on 2026-10-26 (answer C to the second design question) and asked for
the first real working release to be 1.0.0. The release pull request,
[outlook-to-imap pull request #6](https://github.com/martinfrancois/outlook-to-imap/pull/6), was to
be merged only after the live check, as
[outlook-to-imap issue #4](https://github.com/martinfrancois/outlook-to-imap/issues/4) recorded.

The live check is
[outlook-to-imap issue #2](https://github.com/martinfrancois/outlook-to-imap/issues/2). The owner
asked that it be runnable from the issue alone by a different agent. It was rewritten with steps
marked for a human or an agent, exact commands, expected output, a report template and rules for
handling secrets. Its IMAP readback check is a Python script that was tested against GreenMail on
Python 3.8, 3.11 and 3.13; curl was rejected for it, because it printed nothing when a search
matched more than one message and its fetch marks messages as read. The source removal step was
made mandatory because it covers the riskiest code path. The check of a login after 90 days moved
to [outlook-to-imap issue #13](https://github.com/martinfrancois/outlook-to-imap/issues/13). The
owner reported the live check done on 2026-10-26, and 1.0.0 was published the same day.

The release CI dispatch came in
[outlook-to-imap pull request #7](https://github.com/martinfrancois/outlook-to-imap/pull/7).
[outlook-to-imap pull request #8](https://github.com/martinfrancois/outlook-to-imap/pull/8) fixed
it: `fromJSON` on an empty `pr` output failed the whole workflow, so the pull request JSON now
reaches the script as text and `jq` reads `headBranchName`. The repository's default workflow
token permission is read-only, and the Actions token may approve pull request reviews; the owner
approved that setting on 2026-10-26.

The Maven wrapper came in while clearing CI warnings: Maven on the runner logged `Could not transfer
metadata ... HTTP Status: 401` for repositories declared inside the Graph SDK's POM and Flyway's
parent POM. The CI run after the switch had none of those lines, and the wrapper also gave the
Docker build stage the same Maven as CI. The `build` check name and the ruleset were copied from
junkEmailMover's "Required merge checks" ruleset, so the owner's habits carry over.
