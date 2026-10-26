---
status: accepted
date: 2026-10-26
decision-makers: [implementing coding agent, approved by martinfrancois in the design plan]
consulted: []
informed: [Future maintainers and agents working on this repository]
---

# Microsoft Graph through the official SDK, despite its size

## Context and Problem Statement

The service calls four Graph operations: list mail folders, the delta of a folder's messages, a
message's MIME content, and move a message. The official Graph Java SDK covers all of Graph.
`microsoft-graph` 6.71.0 is 64,841,576 bytes on its own, and the released shaded jar is 100,491,016
bytes. The JDK's `HttpClient` and the Jackson library the project already ships could send these
four requests with no new dependency.

At the start of the work, the owner set a general rule: use established, well-maintained libraries
instead of writing the logic by hand. Should Graph go through the SDK, at its size, or through
plain HTTP calls?

## Decision Drivers

* The owner's rule: don't reinvent the wheel where established and well-maintained libraries exist.
* Throttling, paging and error handling are where hand-written Graph clients go wrong
  ([ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md)).
* Graph changes over time, and someone has to keep up with it.
* Disk space and image pull time cost something, once per release.

## Considered Options

* The official Graph Java SDK
* Plain HTTP calls with the JDK's `HttpClient` and Jackson

## Decision Outcome

Chosen option: "The official Graph Java SDK", because the parts that are hard to get right are in
it and maintained by Microsoft: delta paging with `@odata.nextLink` and `@odata.deltaLink`, the
retry handler for 429, 503 and 504, error parsing into `ODataError`, and keeping up with Graph
changes. A throttling bug in hand-written retry code costs more than the jar's size.

The SDK's retry handler waits for `Retry-After` when Microsoft sends it and backs off exponentially
when it doesn't, at most 180 seconds per wait in either case. The numbers and what the service
changes about them are in [ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md).

The same rule picked the other libraries in `pom.xml`: MSAL4J for sign-in and token refresh, Angus
Mail for IMAP `APPEND`, `SEARCH` and `FETCH`, picocli for the command line, Jackson for the YAML
configuration and JSON, Jdbi, Flyway and HikariCP for the database, sqlite-jdbc and the PostgreSQL
driver, and SLF4J with Logback for logs. The health endpoint uses the JDK's built-in
`com.sun.net.httpserver.HttpServer` and the heartbeat the JDK's `java.net.http.HttpClient`, so
neither adds a dependency.

### Consequences

* Good, because Microsoft maintains the paging, retry and error parsing code.
* Good, because the WireMock tests run the real SDK, so an SDK update that changes behavior fails
  the build.
* Bad, because the jar is about 100 MB and the image correspondingly larger.
* Bad, because the SDK brings its own defaults and dependencies the project has to manage. Its
  100 second overall call timeout had to be lifted
  ([ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md)), and its Jackson 2 and
  OpenTelemetry dependencies had known vulnerabilities until they were pinned
  ([ADR 0013](0013-dependency-updates-and-transitive-pins.md)).

### Confirmation

`pom.xml` declares `com.microsoft.graph:microsoft-graph`. `GraphSource` is the only class that calls
Graph, and it contains no paging or retry loop of its own. AGENTS.md has the rule "Prefer an
established library over hand-written protocol, retry or parsing code."

## Pros and Cons of the Options

### The official Graph Java SDK

`GraphServiceClient` with the SDK's default middleware, including Kiota's `RetryHandler`, and
`PageIterator` for paging.

* Good, because the SDK implements delta paging and retries the way Graph documents them.
* Good, because Microsoft updates it when Graph changes.
* Bad, because it is about 65 MB for four endpoints.
* Bad, because its defaults, such as the call timeout, can surprise, and they must be read from its
  code to be sure.

### Plain HTTP calls with the JDK's `HttpClient` and Jackson

Build the four requests by hand, follow `@odata.nextLink` and `@odata.deltaLink`, parse Graph's
error format and write the 429 handling with `Retry-After` and backoff.

* Good, because the jar would shrink by most of its size.
* Good, because every timeout and retry would be under the project's control.
* Bad, because it is protocol, retry and parsing code the owner's rule asks not to write.
* Bad, because the project would have to follow Graph's changes itself.

## More Information

The owner set the library rule on 2026-10-26 at the start of the work. The agent chose the SDK under
that rule the same day and listed this record in the plan the owner confirmed. The first review
round on [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)
applied the same rule to the `/health` endpoint, which had built its JSON by hand and now uses
Jackson.

Sizes measured on 2026-10-26: `microsoft-graph-6.71.0.jar` is 64,841,576 bytes, and the release
asset `outlook-to-imap-1.0.0.jar` is 100,491,016 bytes.

Revisit this if Microsoft ships a modular SDK that can be used for mail alone, or if the jar size
causes a real problem for someone.
