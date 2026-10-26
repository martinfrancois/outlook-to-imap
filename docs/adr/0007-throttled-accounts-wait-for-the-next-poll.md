---
status: accepted
date: 2026-10-26
decision-makers: [implementing coding agent, approved by martinfrancois in the design plan]
consulted: ["Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# Throttled accounts wait for the next poll

## Context and Problem Statement

Microsoft allows 10,000 Graph requests in 10 minutes per app and source account
([Outlook service limits](https://learn.microsoft.com/en-us/graph/throttling-limits#outlook-service-limits)).
A poll costs one request per 100 messages listed, one per message copied and one per message
removed. A first poll of a 9,000-message folder with source removal on needs about 18,100
requests, so it will be throttled. Microsoft answers with HTTP 429, and sometimes 503 or 504, often
with a `Retry-After` header.

How should the service wait and retry, how long may one request wait in total, and what happens to
the other accounts while one is throttled? The owner asked for this limit to be recorded in an ADR
and for tests that show the service handles it.

## Decision Drivers

* Retry code must come from an established library, not be written by hand
  ([ADR 0005](0005-graph-sdk-despite-its-size.md)).
* A throttled source account must not hold up the other accounts.
* Nothing may be lost or copied twice when a poll gives up on an account; the next poll must carry
  on.
* The waiting the service promises in the README must match what the library does.
* Tests must play real 429 and 503 replies against the real SDK.

## Considered Options

* The Graph SDK's retry handler with 5 retries, no overall call timeout, and the account skipped
  until the next poll
* Keep the SDK's default 100 second call timeout
* Stop retrying when `Retry-After` asks for more than the SDK's cap
* Hand-written retry and backoff code

## Decision Outcome

Chosen option: "The Graph SDK's retry handler with 5 retries, no overall call timeout, and the
account skipped until the next poll", because it uses the retry code Microsoft maintains, lets every
wait the handler starts run to its end, and keeps throttling of one source account from holding up
the others.

The rules:

* `GraphSource` builds its client with a `RetryHandlerOption` of 5 retries (`MAX_RETRIES`) and the
  SDK's default 3 second base delay. The handler retries 429, 503 and 504. It waits as long as
  `Retry-After` asks, or backs off exponentially when the header is missing. Every wait,
  `Retry-After` included, is capped at 180 seconds by the SDK.
* `GraphSource.httpClient` sets OkHttp's `callTimeout` to zero. The connect and read timeouts of
  each attempt stay.
* When the retries run out, `GraphSource.translate` throws `ThrottledException` for 429, 503 and
  504 alike. Its message says Microsoft "throttled this account or was unavailable".
* `Poller` ends that account for this poll, keeps the checkpoint of the folder it was reading, and
  goes on with the next account. Receipts let the next poll continue where this one stopped.

### Consequences

* Good, because a throttled account costs nothing to the others, and no work is lost.
* Good, because the retry behavior follows the SDK, and an SDK update that changes it fails the
  WireMock tests.
* Bad, because one request can now wait up to 5 times 180 seconds, 15 minutes in all, before it
  gives up. A stop request during that wait outlasts the shutdown grace. The lease handling in
  [ADR 0015](0015-one-poll-at-a-time.md) covers that case.
* Bad, because a Microsoft outage that answers 503 or 504 is reported as "throttled or unavailable",
  not as an outage of its own.
* Neutral, because the handler's 180 second cap means the service may retry before the time a long
  `Retry-After` asked for. Microsoft then throttles that retry too, and the account waits for the
  next poll.

### Confirmation

Tests in `GraphSourceTest` use WireMock as Graph and the real SDK:
`waitsForRetryAfterWhenThrottledThenSucceeds`, `backsOffWithoutRetryAfterThenSucceeds`,
`givesUpAfterTheRetryLimitWhenThrottlingContinues`, `throttlingInTheMiddleOfPagingIsRetried`, and
`retriesAreNotCutShortByAnOverallCallTimeout`, which checks that the call timeout is zero and the
read timeout is not. `PollerTest.aThrottledAccountWaitsForTheNextPollWhileTheOtherAccountContinues`
checks the account isolation. The tests shorten the delays with `RetryHandlerOption(null, 2, 0)` in
`FakeGraph`.

After an SDK update, read the Kiota constants again, for example with
`javap -constants -cp microsoft-kiota-http-okHttp-<version>.jar com.microsoft.kiota.http.middleware.options.RetryHandlerOption`.

## Pros and Cons of the Options

### The Graph SDK's retry handler with 5 retries, no overall call timeout, and the account skipped until the next poll

The SDK's Kiota `RetryHandler` does the waiting. The project only picks the number of retries,
removes the overall call timeout that would cut the waits short, and decides what a request that
still fails means for the poll.

* Good, because no retry or backoff code lives in this repository.
* Good, because each wait the handler starts is allowed to finish.
* Good, because the other accounts of the poll keep going.
* Bad, because a single stuck request can take up to 15 minutes to give up.

### Keep the SDK's default 100 second call timeout

Use the OkHttp client from `GraphClientFactory` as it comes. Kiota's `KiotaClientFactory` sets
connect, read and call timeouts of 100 seconds each.

* Good, because no setting differs from the SDK default.
* Bad, because the retry handler is an OkHttp application interceptor, so the call timeout covers
  all of its waits. With `Retry-After: 120`, the handler sleeps 120 seconds, OkHttp cancels the call
  at 100 seconds, and the account fails with a generic error instead of `ThrottledException`.

### Stop retrying when `Retry-After` asks for more than the SDK's cap

Read the `Retry-After` header before retrying and give up on the account at once when it asks for
more than 180 seconds, instead of retrying early.

* Good, because the service would never send a request Microsoft asked it to hold back.
* Bad, because it needs custom code around the SDK's handler, which the library rule argues against.
* Bad, because the early retry costs one more throttled request and nothing else, so the extra code
  buys little.

### Hand-written retry and backoff code

Send plain HTTP requests and write the 429 handling, `Retry-After` parsing and backoff in this
repository.

* Good, because every limit would be under the project's control.
* Bad, because a throttling bug in hand-written code costs more than the SDK's size, see
  [ADR 0005](0005-graph-sdk-despite-its-size.md).

## More Information

The Kiota numbers were read from the bytecode of `microsoft-kiota-http-okHttp` 1.9.2, the version
Graph SDK 6.71.0 brings in, on 2026-10-26:

* `RetryHandlerOption.MAX_RETRIES` is 10, the most a caller may ask for. `DEFAULT_MAX_RETRIES` is 3,
  `DEFAULT_DELAY` is 3 seconds and `MAX_DELAY` is 180 seconds.
* `RetryHandler.getRetryAfter` parses `Retry-After` as seconds or as a date, falls back to
  exponential backoff without the header, and passes either value through
  `Math.min(value, 180000.0)`. Every wait, `Retry-After` included, is at most 180 seconds.
* `KiotaClientFactory.create` sets `connectTimeout`, `readTimeout` and `callTimeout` to
  `Duration.ofSeconds(100)` each.

History. The first version set a 10 minute call timeout. The first review round on
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5) warned
that 10 minutes could cut 5 times 180 seconds of retries short, and the explicit timeout was
removed. The second round found that `GraphClientFactory` then fell back to Kiota's 100 seconds,
which was worse, and the timeout became zero. The third round found that the README and this record
promised to honour `Retry-After` without limit; the wording was corrected to the 180 second cap.
The first review round also pointed out that 503 and 504 count as throttling; that was kept because
the SDK's handler treats them alike, and the error message was changed to say "or was unavailable".

The owner asked on 2026-10-26 for the rate limit to be documented in an ADR and covered by tests.
[ADR 0001](0001-each-user-registers-their-own-app.md) explains why one app registration is enough
for several accounts under these limits.
