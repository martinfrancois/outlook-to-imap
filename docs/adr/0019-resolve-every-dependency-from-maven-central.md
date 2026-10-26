---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26]
informed: [Future maintainers and agents working on this repository]
---

# Resolve every dependency from Maven Central

## Context and Problem Statement

The Graph SDK and Flyway POMs declare repositories of their own: a Microsoft Azure Artifacts feed
(`GraphDeveloperExperiences_Public`) and a GitHub Packages registry
(`flyway-community-db-support`). Both refuse anonymous requests with HTTP 401. Every artifact the
build needs is on Maven Central.

With Maven 3.9.16 this went unnoticed. After Renovate moved the Maven wrapper to 3.10.0
([outlook-to-imap pull request #15](https://github.com/martinfrancois/outlook-to-imap/pull/15)),
every build logged two warnings, one per repository:
`Could not transfer metadata /.meta/prefixes.txt from/to ... HTTP Status: 401`. Maven now asks each
repository for its prefix list before it asks for artifacts. The owner's rule for CI logs is that a
justified warning gets fixed rather than ignored. How should the build treat repositories that
dependencies declare but the build doesn't need?

## Decision Drivers

* The build must not log warnings that carry no information.
* The build should contact only the repositories it actually resolves from, so a dependency can't
  quietly start pulling artifacts from a third-party repository.
* `./mvnw`, CI, the release workflow and the image build stage must behave the same.
* A developer's own Maven settings, such as a company proxy or mirror, must keep working.

## Considered Options

* A mirror of `external:*` to Central, in `.mvn/settings.xml` loaded as global settings
* Turn the prefix filter off with `-Daether.remoteRepositoryFilter.prefixes=false`
* The same mirror, loaded as project settings with `--settings`
* A mirror of `*` instead of `external:*`
* Leave the warnings

## Decision Outcome

Chosen option: "A mirror of `external:*` to Central, in `.mvn/settings.xml` loaded as global
settings", because it removes the warnings by not contacting the two repositories at all, keeps the
build on the one repository it needs, and leaves `~/.m2/settings.xml` in force.

`.mvn/maven.config` holds `--global-settings=${session.rootDirectory}/.mvn/settings.xml`. The mirror
has the id `central`, so a developer's own mirror with that id replaces it, and user settings apply
on top of global ones.

### Consequences

* Good, because builds log no warnings: with an empty local repository, test-scope resolution and
  `./mvnw package` print none.
* Good, because no request leaves for a repository the build doesn't use.
* Bad, because a dependency published only outside Central won't resolve until `.mvn/settings.xml`
  gets an exclusion and a repository for it. The failure is loud, at build time.
* Bad, because a system `mvn` (not `./mvnw`) loses its installation's `conf/settings.xml` for this
  build, since global settings replace that file. The wrapper's own Maven ships an empty one.
* Neutral, because Maven 4 deprecates `--global-settings`. A wrapper update to Maven 4 is a major
  update, which Renovate leaves for a person, and this record is the place to look then.

### Confirmation

`./mvnw -B -Dmaven.repo.local=<empty directory> dependency:resolve -DincludeScope=test` succeeds
and prints no `[WARNING]` line. Running it with `-X` shows
`Reading global settings from <repository>/.mvn/settings.xml` and only `central` in the repository
pipeline. CI job logs on `main` contain no `prefixes.txt` line.

## Pros and Cons of the Options

### A mirror of `external:*` to Central, in `.mvn/settings.xml` loaded as global settings

A settings file in the repository declares one mirror: every repository except `file://` and
`localhost` ones goes to `https://repo.maven.apache.org/maven2`. `.mvn/maven.config` passes it to
every Maven run as global settings.

* Good, because the two repositories are never contacted, so there is nothing to warn about.
* Good, because `.mvn/` is copied into the image build stage and used by CI, so every build path
  gets it.
* Good, because `external:*` leaves local `file://` repositories alone, which a developer may use
  for testing.
* Bad, because of the two costs listed under Consequences.

### Turn the prefix filter off with `-Daether.remoteRepositoryFilter.prefixes=false`

One line in `.mvn/maven.config` stops Maven from asking any repository for its prefix list.

* Good, because it also removes both warnings, which a test with an empty local repository showed.
* Bad, because it switches off a resolver feature for every repository, Central included, instead
  of addressing the two repositories that cause the problem.
* Bad, because the build would still send artifact requests to the two repositories whenever
  Central lacks something.

### The same mirror, loaded as project settings with `--settings`

`.mvn/maven.config` passes the file with `--settings` instead of `--global-settings`.

* Good, because the mirror works the same way.
* Bad, because `--settings` replaces the developer's `~/.m2/settings.xml` for this build, so a
  company proxy, mirror or credentials set there would stop working.

### A mirror of `*` instead of `external:*`

The mirror applies to every repository, local ones included.

* Good, because it is the most familiar form.
* Bad, because it would also send `file://` and `localhost` repositories to Central, which nothing
  needs, and break a developer's local test repository.

### Leave the warnings

Accept the two lines in every build log.

* Good, because nothing changes.
* Bad, because two warnings in every log teach readers to skip warnings, and the build keeps
  contacting repositories it doesn't use.

## More Information

The change is
[outlook-to-imap pull request #28](https://github.com/martinfrancois/outlook-to-imap/pull/28),
which replaced [outlook-to-imap pull request #26](https://github.com/martinfrancois/outlook-to-imap/pull/26)
after its review. The review checked that `${session.rootDirectory}` resolves when Maven runs from
the root, from a subdirectory, with `-f` and from a path containing a space, and that a user's
`~/.m2/settings.xml` profile stays active.
