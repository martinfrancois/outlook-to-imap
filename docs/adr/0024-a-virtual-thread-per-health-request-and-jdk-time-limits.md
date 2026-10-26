---
status: accepted
date: 2026-10-26
decision-makers: ["implementing coding agent, after martinfrancois asked for every audit finding to be fixed in [outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31)"]
consulted: []
informed: [Future maintainers and agents working on this repository]
---

# A virtual thread per /health request, and JDK time limits set before the first server

## Context and Problem Statement

`HealthServer` serves `/health` with the JDK's built-in `com.sun.net.httpserver.HttpServer`. In
1.0.0 it set no executor, so the JDK read every request and ran the handler on its one dispatcher
thread. The JDK also sets no limit on how long a client may take to send a request or read the
reply. The security audit of 1.0.0 (finding F3 in
[outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31))
found that a client that sent half a request line and then stopped blocked every later `/health`
request. Anyone who could reach the port could make the Docker `HEALTHCHECK` fail, and an
orchestrator with a liveness probe would then restart the service again and again.

The JDK has two system properties for the time limits, `sun.net.httpserver.maxReqTime` and
`sun.net.httpserver.maxRspTime`, in seconds. They are process-wide, and the JDK reads them only once.
How does `/health` keep answering while one or more clients stall?

## Decision Drivers

* One client that stops sending or reading must not block the answers to other clients.
* A stalled connection must not stay open forever.
* The fix must work for the image and for a jar started by hand, without extra flags.
* An owner who already sets the JDK properties on the command line keeps their values.

## Considered Options

* A virtual thread per exchange, and both time limits set in `HealthServer` unless already set
* A small fixed pool of daemon threads, with the same time limits
* The time limits as `-D` flags in the `Dockerfile` `ENTRYPOINT`, on the dispatcher thread

## Decision Outcome

Chosen option: "A virtual thread per exchange, and both time limits set in `HealthServer` unless
already set", because it is the only option where neither a few slow clients nor the way the service
is started bring back the stall.

* `HealthServer` passes `Executors.newThreadPerTaskExecutor` with a virtual thread factory to
  `HttpServer.setExecutor`. A slow client holds only its own virtual thread. Virtual threads are
  daemon threads, so they do not keep the JVM alive at shutdown.
* A static initializer in `HealthServer` sets `sun.net.httpserver.maxReqTime` and
  `sun.net.httpserver.maxRspTime` to `CLIENT_TIMEOUT_SECONDS` (10) when the property is not set
  yet. It runs before `HealthServer` creates its server, because the JDK reads both values once,
  when the first server is created.
* A value set on the command line wins, so an owner can still choose another limit.

### Consequences

* Good, because a client that stalls in the middle of a request no longer delays the answer to the
  `HEALTHCHECK`, and its connection closes after about 10 seconds.
* Good, because the jar and the image behave the same with no extra flags.
* Bad, because the properties apply to every JDK `HttpServer` in the process. The service runs no
  other one, but a later one would share the limits.
* Bad, because a server created anywhere in the process before `HealthServer` loads would make the
  JDK read the properties without the limits, and they would then stay off for the whole process.
* Bad, because nothing limits how many connections may be open at once. Each one costs a virtual
  thread for up to 10 seconds. Binding `/health` to `127.0.0.1` by default
  ([ADR 0020](0020-verified-postgresql-tls-and-loopback-health-by-default.md)) keeps that to
  clients on the same host unless the owner opens the port.

### Confirmation

Tests in `PollServiceTest`: `aHalfSentRequestDoesNotKeepTheHealthEndpointFromAnsweringOthers`
answers a second client while the first has sent only `GET /hea`;
`theHealthEndpointClosesAConnectionThatStallsMidRequest` checks that the server closes a half-sent
request within `CLIENT_TIMEOUT_SECONDS` plus 5 seconds; and
`theHealthEndpointClosesAConnectionThatStopsReadingTheReply` checks that the server closes a
connection that stops reading a reply of about 12 MB. `HealthServer` points at this record.

## Pros and Cons of the Options

### A virtual thread per exchange, and both time limits set in `HealthServer` unless already set

Each exchange runs on a new virtual thread. `HealthServer` sets the two JDK properties itself,
before the JDK reads them.

* Good, because the number of slow clients does not decide whether `/health` answers.
* Good, because it needs nothing from the start command.
* Bad, because setting a process-wide system property from a class is a side effect that a reader
  of `Main` does not see.

### A small fixed pool of daemon threads, with the same time limits

`HttpServer.setExecutor` gets `Executors.newFixedThreadPool` with, for example, two daemon threads.
The audit proposed this.

* Good, because the number of threads stays bounded.
* Bad, because two slow connections at once fill a pool of two, and the stall is back for up to the
  time limit. Any client can open more connections than the pool has threads, so every pool size has
  this problem.

### The time limits as `-D` flags in the `Dockerfile` `ENTRYPOINT`, on the dispatcher thread

Keep the single dispatcher thread and pass `-Dsun.net.httpserver.maxReqTime=10` and
`-Dsun.net.httpserver.maxRspTime=10` to java in the image.

* Good, because no code changes and the limits are visible in the start command.
* Bad, because a jar started by hand gets no limit, which is the case where `/health` was most
  exposed.
* Bad, because one stalled client still blocks every other request for up to 10 seconds, and a
  client that opens a new stalled connection every 10 seconds blocks `/health` for good.

## More Information

In JDK 21 and JDK 25, `sun.net.httpserver.ServerConfig` reads `sun.net.httpserver.maxReqTime` and
`sun.net.httpserver.maxRspTime` in its static initializer, and `ServerImpl` copies them into static
final fields. Both default to -1, which means no limit. The JDK checks the limits on a timer that
runs every second by default (`sun.net.httpserver.timerMillis`).

A probe on 2026-10-26 confirmed when the JDK reads the values, on OpenJDK 25.0.4.1 and Temurin
21.0.11. It created one server, set `maxReqTime` to 1 and created a second server, then sent
`GET / HT` to the second one. The connection was still open after 6 seconds. With the property set
before the first server, the server closed the same connection after about 2 seconds. The commit
that added the limits found the same with 10 seconds on JDK 25: the server closed a half-sent request
after 10 seconds, where without the limit it waited forever.

The choice of a virtual thread per exchange over the audit's fixed pool was the implementing coding
agent's.
