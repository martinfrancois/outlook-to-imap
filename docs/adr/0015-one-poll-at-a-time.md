---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26, "Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# One poll at a time, across processes

## Context and Problem Statement

A receipt only stops a second upload if the first one wrote it before the second looked. Two polls
running at the same time over the same database both find no receipt and both upload the same
message. That can happen inside one process, when a poll runs longer than the interval, and across
processes, when a manual `once` runs next to the container's `run`, or when a scheduler such as a
Kubernetes CronJob starts a new run before the last one ended.
[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) asked to
poll on startup, then at an interval, and to prevent overlapping runs.

A poll can also be stopped half way, by `docker stop`, Ctrl-C or a lost lease. How does the service
keep polls from overlapping, stop cleanly, and report a poll that did not finish?

## Decision Drivers

* Two processes sharing a database must never upload at the same time.
* A crashed process must not block polling for long.
* A long first poll, slowed by throttling, must keep its exclusive right for as long as it runs.
* `docker stop` must not cut a message in half, and must not leave the next start blocked.
* A poll that did not finish must not look like a success in `status` or to the heartbeat.
* Exit codes must follow the usual conventions, so schedulers and shells read them correctly.

## Considered Options

* A lease row in the database, renewed during the poll, plus a single-threaded scheduler
* An in-process lock only

## Decision Outcome

Chosen option: "A lease row in the database, renewed during the poll, plus a single-threaded
scheduler", because the database is the one thing every process that could upload shares,
including processes on other machines that use the same PostgreSQL server.

Within one process (`PollService`):

* A single-threaded `ScheduledThreadPoolExecutor` runs the polls. Each poll schedules the next one
  `pollInterval` after it ends, so polls never overlap. A `ReentrantLock` also serialises
  `pollOnce`.
* A stop sets a flag that the poll checks between messages, so the current message finishes. The
  next scheduled poll is dropped.

Across processes (table `poll_lease`, one row with id 1):

* A poll takes the lease if it is free, expired, or already its own. The holder is
  `<hostname>/<random UUID>`. The lease lasts 15 minutes.
* The lease is renewed every 3 minutes (a fifth of the lease) on a timer and after every message.
  Renewal only extends a lease this holder still has.
* A poll that loses the lease stops at the next message.
* At the end of a poll, the release waits for a renewal in flight, then frees the lease.
* A service that finds the lease taken tries again after one renewal period, not a whole interval.
* A dry run takes no lease, because it copies and removes nothing.

Stopping (`Main`):

* `run` and `once` both register a shutdown hook that stops the poll at the next message and waits
  up to 25 seconds (`SHUTDOWN_GRACE`). When the grace runs out, the hook releases the lease before
  the JVM exits. The README and `examples/compose.yaml` give the container a 30 second stop timeout.
* An interrupted poll is neither a success nor a failure. It keeps the last success time, sends no
  heartbeat, and records "poll interrupted before every folder was read". The next poll finishes the
  work.
* `once` exits 0 when every account worked, 1 when an account failed or the poll was interrupted,
  2 for an invalid configuration, and 3 when another process held the lease. A command ended by a
  signal exits with 128 plus the signal: 143 for SIGTERM, 130 for Ctrl-C.

### Consequences

* Good, because no two processes with the same database ever upload at the same time.
* Good, because a crashed holder blocks polling for at most 15 minutes.
* Good, because `docker stop` during a big backlog leaves no half message, no stuck lease and no
  false success.
* Bad, because every message costs one more database write for the lease renewal.
* Bad, because a poll that hangs keeps renewing its lease for as long as the process lives. `/health`
  reports that case, because only handled messages count as progress there
  ([ADR 0016](0016-monitoring-and-failure-isolation.md)).
* Neutral, because a Graph request that is waiting out throttling can take up to 15 minutes
  ([ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md)). A stop during that wait outlasts
  the grace, so the hook releases the lease and the next start reconciles the message.

### Confirmation

`StoreTest.onlyOneHolderGetsTheLeaseUntilItIsReleasedOrExpires`; in `PollServiceTest`:
`doesNotPollWhileAnotherProcessHoldsTheLease`, `aPollThatLosesTheLeaseStopsBeforeTheNextMessage`,
`aDryRunIgnoresTheLeaseBecauseItCopiesAndRemovesNothing`,
`aServiceThatFindsTheLeaseTakenTriesAgainBeforeTheNextInterval`,
`aStopDuringASinglePollReleasesTheLeaseWhenTheGraceRunsOut` and
`aStopThatOutlastsItsGraceReleasesTheLeaseForTheNextStart`. For interrupted polls:
`PollerTest.aStopRequestEndsThePollAtAMessageBoundaryAndKeepsTheCheckpoint` and
`StoreTest.aFailedPollKeepsTheLastSuccess`. The exit codes are constants in `Main`.

## Pros and Cons of the Options

### A lease row in the database, renewed during the poll, plus a single-threaded scheduler

One row in `poll_lease` says who polls and until when. A timer and every handled message push the
expiry forward.

* Good, because it works for every process that shares the database, on any machine.
* Good, because it expires by itself when the holder dies.
* Bad, because the renewal logic has corner cases, which took three review rounds to find.

### An in-process lock only

Serialise polls inside one process and nothing more.

* Good, because it needs no table and no timers.
* Bad, because a manual `once` next to the running container, or overlapping CronJob runs, would
  upload the same messages twice.

## More Information

The review rounds on
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5) shaped
the lease:

* Round 1 found that renewal ignored a lost lease, and that a first listing under throttling could
  take more than 15 minutes before the first message, so a second process could take the expired
  lease and upload duplicates. The renewal timer fixed both.
* Round 2 found that a renewal still running could land after the release and block the next `once`
  for 15 minutes; the release now waits for it. It kept the per-message renewal despite its extra
  write, because it notices a lost lease before the next upload rather than up to 3 minutes later.
  The same round found that an interrupted poll saved a last success and pinged the heartbeat, so a
  `docker stop` during a 9,000-message backlog showed success. Polls now carry an `interrupted`
  flag.
* Round 3 made renewal extend only a lease the holder still has, gave `once` the same shutdown hook
  as `run`, released the lease when the grace runs out, and made a service that finds the lease
  taken retry after one renewal period. Without that, a crash turned at most 15 minutes of stale
  lease into a whole interval without a poll.

The review called the database lease more than the issue strictly asked for, and accepted it. The
service tests were run 8 times under parallel load to check that the timing-based tests are not
flaky.

`SHUTDOWN_GRACE` was 60 seconds in the first version and became 25 seconds before the release; the
session recorded no further reason for the value. The README first said `run` exits 0 after a stop.
A check with the jar showed 143 after SIGTERM and 130 after Ctrl-C, and
[outlook-to-imap pull request #12](https://github.com/martinfrancois/outlook-to-imap/pull/12)
corrected the README instead of changing the code.
