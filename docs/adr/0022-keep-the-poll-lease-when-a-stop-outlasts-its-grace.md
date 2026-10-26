---
status: accepted
date: 2026-10-26
decision-makers: ["implementing coding agent, after martinfrancois asked for every audit finding to be fixed in [outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31)"]
consulted: []
informed: [Future maintainers and agents working on this repository]
---

# Keep the poll lease when a stop outlasts its grace

## Context and Problem Statement

[ADR 0015](0015-one-poll-at-a-time.md) has a stop wait up to 25 seconds (`SHUTDOWN_GRACE`) for the
poll to end, and release the database lease when that grace runs out, so a restarted process is
not blocked until the lease expires. The security audit of 1.0.0 (finding F11 in
[outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31))
found that the release comes while `Poller.poll` is still inside a message: it checks the stop
flag only between messages. A `once` started in that window takes the lease, finds the message's
pending receipt, finds no copy at the destination yet, and uploads it again. What should a stop
do with the lease when the grace runs out?

## Decision Drivers

* No two processes may work on the same message at the same time
  ([ADR 0015](0015-one-poll-at-a-time.md)).
* A message must not be uploaded twice because of a stop.
* A process that exits must not block polling for longer than a crash would.

## Considered Options

* Keep the lease and let it expire
* Release the lease when the grace runs out

## Decision Outcome

Chosen option: "Keep the lease and let it expire", because the poll may still be uploading when
the grace runs out, and only the lease keeps another process away from that message.

`PollService.stop` no longer releases the lease when the grace runs out. The poll releases it in
its `finally` block if it ends before the JVM exits. Otherwise the lease expires 15 minutes
(`LEASE`) after its last renewal, the same as after a crash.

### Consequences

* Good, because a stop can no longer lead to a second upload of the message in flight.
* Bad, because after a stop that outlasts its grace, the next start waits up to 18 minutes before
  it polls: 15 for the lease plus up to 3 for the retry period. The README says so.
* Neutral, because a stop that ends within the grace, the usual case, releases the lease as before.

### Confirmation

In `PollServiceTest`: `aStopThatOutlastsItsGraceDuringASinglePollKeepsTheLeaseUntilThePollEnds`
and `aStopThatOutlastsItsGraceKeepsTheLeaseWhileTheUploadHangs` check that another holder cannot
take the lease after such a stop, and can once the poll has ended.
`aStopThatEndsWithinItsGraceReleasesTheLeaseForTheNextStartAtOnce` checks that another holder can
take the lease as soon as a stop within the grace returns.

## Pros and Cons of the Options

### Keep the lease and let it expire

The stop returns without touching the lease; the poll's own release or the expiry frees it.

* Good, because the lease means what it says for as long as an upload may run.
* Bad, because a restart after such a stop is delayed like a restart after a crash.

### Release the lease when the grace runs out

The stop frees the lease itself before the JVM exits, as [ADR 0015](0015-one-poll-at-a-time.md)
first decided.

* Good, because a restarted process can poll at once.
* Bad, because the restarted process, or a `once`, may upload the message that the stopped poll
  is still sending.

## More Information

The case is rare: it needs a stop during a message that takes more than 25 seconds, such as a
Graph request waiting out throttling ([ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md)),
and another process that starts within those seconds. The audit rated it informational and first
suggested only a comment. In review round 1 of
[outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31), the
owner asked that every audit finding be fixed in that pull request, F11 included. The owner did not
choose how: the implementing coding agent chose to keep the lease until it expires.
