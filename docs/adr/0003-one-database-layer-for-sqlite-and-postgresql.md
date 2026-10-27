---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent, "Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# One database layer for SQLite and PostgreSQL

## Context and Problem Statement

The service keeps receipts, checkpoints, per-account status and the poll lease in a database. SQLite
in the state directory needs nothing else to run, which suits a single container. But some people
keep container volumes on NFS or SMB, and SQLite's own documentation warns that its locks "have been
known to operate incorrectly for some network filesystems", which "has led to database corruption",
and recommends a client/server database such as PostgreSQL in that case
([SQLite Over a Network](https://www.sqlite.org/useovernet.html)).

The owner asked that SQLite not be the only choice, so someone on a network share can use
PostgreSQL, and that the database layer ideally not depend on the engine. How can the service
support both engines without two code paths?

## Decision Drivers

* SQLite stays the default, so a single container needs nothing else.
* PostgreSQL must work for state on a network file system.
* No engine gets code of its own that the other doesn't run.
* Both engines must run in CI on every change.
* The schema is four small tables.
* Database access and migrations come from established libraries.

## Considered Options

* Plain SQL through Jdbi that both engines accept, with one set of Flyway migrations
* SQLite only
* An ORM
* One data access implementation per engine

## Decision Outcome

Chosen option: "Plain SQL through Jdbi that both engines accept, with one set of Flyway migrations",
because one set of statements and migrations runs unchanged on both engines, so both are tested by
the same tests and neither can drift.

The rules:

* `database.url` picks the engine: `jdbc:sqlite:` (the default, `<stateDirectory>/outlook-to-imap.db`)
  or `jdbc:postgresql:`. `ConfigLoader` rejects any other prefix.
* `Store` uses Jdbi and HikariCP. Every statement is SQL both engines accept: upserts with
  `ON CONFLICT ... DO UPDATE`, times as epoch milliseconds in `BIGINT`, no engine-specific types.
  Error texts are `TEXT`, and `Store.MAX_ERROR_LENGTH` (2000 characters) keeps a pathological error
  message from bloating the database.
* Flyway creates and upgrades the schema from `src/main/resources/db/migration`. A released
  migration is never edited; a schema change is a new migration.
* For SQLite, `busy_timeout` is 30 seconds, because SQLite locks the whole file per write and the
  health endpoint may read while a poll writes. The pool holds 2 connections for SQLite and 4 for
  PostgreSQL.

### Consequences

* Good, because every store test and the end-to-end backlog test run on both engines.
* Good, because a single container still needs no database server.
* Bad, because the SQL is limited to what both engines accept, so engine features such as
  PostgreSQL's richer types are off limits.
* Bad, because adding a third engine is not only a dependency. It needs its JDBC driver and its
  Flyway database module in `pom.xml`, a new accepted prefix in `ConfigLoader` and `Config.Database`,
  and the engine in the test parameter of `StoreTest` and `PollerTest`. No query should need to
  change.
* Neutral, because only SQLite and PostgreSQL are supported. The README makes no claim about other
  engines, since nothing tests them.

### Confirmation

The tests of `StoreTest` and `PollerTest.copiesTheBacklogOnceAndNeverAgainEvenAfterTheCopiesAreDeleted`
run on SQLite and on PostgreSQL through Testcontainers (`postgres:18-alpine`); `./mvnw -B verify`
runs them in CI. `ConfigLoaderTest.rejectsAnotherDatabaseEngine` checks that other URLs are
rejected. The first line of `V1__receipts_checkpoints_status.sql` says the SQL must stay plain, and
AGENTS.md repeats the rule.

## Pros and Cons of the Options

### Plain SQL through Jdbi that both engines accept, with one set of Flyway migrations

Jdbi runs hand-written SQL over a HikariCP pool. Flyway applies the same migration files to either
engine.

* Good, because there is one code path for both engines.
* Good, because the SQL is short and readable for four tables.
* Bad, because a statement that works on one engine and not the other fails only in that engine's
  test run, so both must keep running in CI.

### SQLite only

Keep SQLite as the only engine.

* Good, because there is one engine to test and no driver for a server.
* Bad, because SQLite on NFS or SMB risks a corrupt database, and the owner asked for an
  alternative for exactly that case.

### An ORM

Map the tables to entity classes with an ORM such as Hibernate and let it generate the SQL for
each engine.

* Good, because the ORM would handle engine differences.
* Bad, because it is more machinery than four small tables need.

### One data access implementation per engine

Write a store class for SQLite and another for PostgreSQL behind a common interface.

* Good, because each engine could use its own features.
* Bad, because there would be two code paths to keep in step, and a fix in one could miss the other.

## More Information

Flyway's SQLite support is part of `flyway-core`. On 2026-10-26 Maven Central had no
`flyway-database-sqlite` module, and `flyway-core` 13.10.3 contains
`org/flywaydb/core/internal/database/sqlite/`. PostgreSQL needs `flyway-database-postgresql`, which
Renovate keeps in the same group as `flyway-core` ([ADR 0013](0013-dependency-updates-and-transitive-pins.md)).

The error columns were first `VARCHAR(2000)`. The first review round on
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5) found
the 2000 duplicated between code and schema, and the columns became `TEXT`. That change, and the
rename of the hash column for [ADR 0014](0014-verify-each-upload-and-settle-unknown-ones-by-content.md),
edited `V1` in place before 1.0.0 shipped it; from 1.0.0 on, migrations are only added.

During the live check, a diagnostic helper held a SQLite read transaction across network calls and
delayed a scheduled poll with `SQLITE_BUSY` once. Running the checks one after another fixed it; no
code changed. Nobody recorded a reason for the pool sizes of 2 and 4.

Login tokens are not in the database, see
[ADR 0008](0008-device-code-sign-in-with-a-private-token-file-per-account.md).
