---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26, "Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# Monitoring and failure isolation

## Context and Problem Statement

The service runs unattended. Several things can go wrong, and they need different responses. A
stuck process needs a restart. An expired login needs the user to run `authorize`. A throttled
mailbox or an unreachable IMAP server needs only time. One malformed message needs nothing but a
retry on the next poll.
[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) asked for a
documented monitoring and alerting integration, and to isolate failures so one bad message or
account does not block the others.

How does a failure stay with the message, folder or account it belongs to, and how does each kind
of failure reach the person who can fix it?

## Decision Drivers

* A container health check triggers restarts, so it must only fail when a restart helps.
* A failure that needs the user, such as an expired login, must still raise an alert.
* A stopped container or dead host must raise an alert too, without the service's help.
* One failing message, folder, destination or account must not stop the rest.
* A long first poll through a big backlog must not look stuck.
* No dependency for something the JDK already does.

## Considered Options

* `/health` reports only whether polls make progress; account failures go to a heartbeat URL
* `/health` returns 503 when any account fails

## Decision Outcome

Chosen option: "`/health` reports only whether polls make progress; account failures go to a
heartbeat URL", because a restart fixes a stuck poll loop but never a login, and a heartbeat service
alerts both on reported failures and on pings that stop coming.

`/health` (`HealthServer`, `PollService.healthy`):

* Answers 200 while a poll finished, or a message was handled, less than two intervals plus 15
  minutes ago (`HEALTH_MARGIN`). Every message counts, so a long first poll stays healthy.
* Answers 503 otherwise, and while the service is stopping.
* The body is JSON with each account's last attempt, last success, counts and last error. When the
  status can't be read from the database, it answers 503 with the error in the body.
* Runs on the JDK's `com.sun.net.httpserver.HttpServer` on `monitoring.healthPort`, 8080 by
  default; `0` turns it off. The image's `HEALTHCHECK` calls port 8080.

The heartbeat (`Heartbeat`):

* After every poll that ran to the end, it POSTs the summary to `monitoring.heartbeatUrl` when
  every account worked, and to that URL plus `/fail` when one did not. An interrupted poll sends
  nothing ([ADR 0015](0015-one-poll-at-a-time.md)).
* healthchecks.io, or any service that accepts the same two URLs, alerts on a `/fail` ping and on
  pings that stop coming, which covers a stopped container, a dead host and a login that needs
  renewing.

Failure isolation (`Poller`):

* A failed message is counted, its error is recorded on its receipt, and its folder's checkpoint
  stays where it was, so the next poll sees it again. A message that no longer exists counts as
  skipped.
* An unreachable destination fails that folder mapping and drops that connection. An unreadable
  destination password file counts as an unreachable destination.
* A throttled account, or one that needs a new login, ends that account's poll. A token check runs
  first, so a missing login fails before any folder work.
* The next account always runs.

### Consequences

* Good, because the container restarts only when the poll loop is stuck, never in a loop over an
  expired login.
* Good, because every failure the user must act on reaches the heartbeat, `status`, the log and the
  `/health` body.
* Good, because one bad message, folder, destination or account never stops the others.
* Bad, because a user without a heartbeat URL learns about an expired login only from the log,
  `status` or the `/health` body.
* Bad, because the image's health check is fixed to port 8080. Users who change `healthPort` or set
  it to `0` must replace or turn off the check; the README says how.

### Confirmation

`PollServiceTest.healthFollowsPollProgressAndTheEndpointShowsEachAccount`,
`aSuccessfulPollPingsTheHeartbeat` and `aFailedAccountPingsTheFailureUrlWithTheReason`. In
`PollerTest`: `oneBadMessageDoesNotStopTheOthersAndIsRetriedNextPoll`,
`anAccountThatNeedsANewLoginDoesNotStopTheOtherAccount` and
`aThrottledAccountWaitsForTheNextPollWhileTheOtherAccountContinues`.
`ImapDestinationTest.anUnreadablePasswordFileMakesOnlyThisDestinationUnavailable`.
`scripts/smoke-image.sh` checks that the image answers `/health`.

## Pros and Cons of the Options

### `/health` reports only whether polls make progress; account failures go to a heartbeat URL

Two channels: the health endpoint answers "is the loop alive", the heartbeat answers "did every
account work".

* Good, because each signal leads to the action that fixes it: a restart, or the user.
* Good, because a heartbeat service also notices when the whole host is gone.
* Bad, because full alerting needs an outside service such as healthchecks.io.

### `/health` returns 503 when any account fails

Make the health endpoint fail whenever the last poll had a failed account.

* Good, because one endpoint would show every problem.
* Bad, because the container runtime would restart the service over a failure a restart can't fix,
  such as a login that needs `authorize`, again and again.

## More Information

The agent proposed the heartbeat and the health endpoint on 2026-10-26, and they were part of the
plan the owner confirmed. The review rounds on
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5) changed
the details: round 1 made the 503 path send a JSON reply instead of closing the connection, built
the JSON with Jackson instead of by hand, documented the fixed health check port, and made an
unreadable password file fail only its destination. Round 2 split `HEALTH_MARGIN` from the lease
length, to cover one slow message.

503 and 504 from Graph count as throttling, so an outage of Microsoft's side ends an account the
same way ([ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md)). A folder that disappears
between polls currently fails its mapping with a misleading message, see
[outlook-to-imap issue #25](https://github.com/martinfrancois/outlook-to-imap/issues/25).
