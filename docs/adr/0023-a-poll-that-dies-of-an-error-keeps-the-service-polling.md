---
status: accepted
date: 2026-10-26
decision-makers: ["implementing coding agent, after martinfrancois asked for every audit finding to be fixed in [outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31)"]
consulted: []
informed: [Future maintainers and agents working on this repository]
---

# A poll that dies of an Error keeps the service polling

## Context and Problem Statement

`PollService.pollAndScheduleNext` runs one poll and then schedules the next. In 1.0.0 it caught only
`RuntimeException`. The security audit of 1.0.0 (finding F7 in
[outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31))
found that an `Error`, such as an `OutOfMemoryError` from a message too big for the heap, left the
method before it scheduled the next poll. The process stayed up and never polled again. `/health`
turned 503 only after two intervals plus 15 minutes, and Docker marks such a container unhealthy
without restarting it.

A sender controls a message's size up to what Outlook accepts, and a hostile IMAP server can
announce a literal of any size. The large allocations are per message: the downloaded bytes, their
CRLF copy and Angus Mail's append buffer. What should the service do when a poll ends with an
`Error`?

## Decision Drivers

* A service that runs unattended must not stay up while it copies nothing.
* The image runs under a restart policy. A jar started by hand has nothing that starts it again.
* A failure that repeats on every poll must show in the log, not end the loop.
* Memory taken by one message becomes garbage once the failed poll's stack unwinds.

## Considered Options

* Catch `Throwable`, and exit on `OutOfMemoryError` in the image only
* Exit the process on any `Error`
* Catch only `RuntimeException`, as 1.0.0 did

## Decision Outcome

Chosen option: "Catch `Throwable`, and exit on `OutOfMemoryError` in the image only", because it
keeps polling both where the service runs under a restart policy and where it runs as a plain jar.

* `PollService.pollAndScheduleNext` catches `Throwable`, logs it at error level as "poll failed",
  and schedules the next poll. The poll's `finally` blocks release the poll lock and the database
  lease on the way out.
* The `Dockerfile` `ENTRYPOINT` runs java with `-XX:+ExitOnOutOfMemoryError`. In the container an
  `OutOfMemoryError` ends the JVM before any catch runs, and the restart policy from the README and
  `examples/compose.yaml` starts a clean one.
* A jar run by hand has no such flag, so the catch carries on after an `OutOfMemoryError`. Exiting
  there would end the service for good, because nothing would start it again.

### Consequences

* Good, because an oversized message or any other `Error` no longer leaves a process that looks
  alive and copies nothing.
* Good, because the container gets a fresh JVM after an `OutOfMemoryError` instead of relying on the
  heap to recover.
* Bad, because a hand-run jar keeps running after an `OutOfMemoryError`. If the cause is a leak and
  not one big message, every later poll fails as well. Each failure is logged at error level, sends
  no heartbeat, and leaves `/health` without progress, so it turns 503 after two intervals plus 15
  minutes.
* Bad, because a message too big for the heap is tried again on every poll, since the failed poll
  saves no checkpoint for its folder. Until the heap grows or the message is removed, the accounts
  after it in the configuration are not polled, and in the container every try costs a restart.

### Confirmation

`PollServiceTest.aPollThatDiesOfAnErrorStillSchedulesTheNextOne` throws an `OutOfMemoryError` from
the first login and checks that a later poll copies the message and `healthy()` is true. The
`ENTRYPOINT` in `Dockerfile` contains `-XX:+ExitOnOutOfMemoryError`. `PollService` and `Dockerfile`
point at this record.

## Pros and Cons of the Options

### Catch `Throwable`, and exit on `OutOfMemoryError` in the image only

`pollAndScheduleNext` treats an `Error` like any other failed poll. The image adds a JVM flag that
turns an `OutOfMemoryError` into an exit.

* Good, because both ways of running the service keep polling.
* Good, because the container does not depend on a heap that ran out once.
* Bad, because a hand-run jar continues after an `OutOfMemoryError` on a heap that may be damaged
  by a leak.

### Exit the process on any `Error`

Catch `Error` in `pollAndScheduleNext`, log it and end the JVM.

* Good, because no poll ever runs on a JVM that once threw an `Error`.
* Bad, because a jar run by hand stops copying mail until someone starts it again.
* Bad, because an `Error` that is not about memory, such as a `StackOverflowError`, would end the
  service too, although the heap is still intact.

### Catch only `RuntimeException`, as 1.0.0 did

Leave an `Error` uncaught.

* Good, because it needs no change.
* Bad, because the first `Error` ends the poll loop silently. The process stays up, Docker does not
  restart an unhealthy container, and no mail is copied until someone notices.

## More Information

The audit rated F7 low and proposed both the catch and the JVM flag. The owner asked in review
round 1 of [outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31)
for every audit finding to be fixed in that pull request. The choice to keep a hand-run jar polling
after an `OutOfMemoryError`, instead of exiting, was the implementing coding agent's.
