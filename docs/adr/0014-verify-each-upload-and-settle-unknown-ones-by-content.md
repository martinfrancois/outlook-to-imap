---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26, "Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# Verify each upload, and settle unknown ones by content

## Context and Problem Statement

The service uploads each message with IMAP `APPEND`. An upload can fail in a way that leaves its
outcome unknown: the connection drops or the process dies after the server stored the message but
before the reply arrived. Uploading again then creates a duplicate, which the service can never
delete ([ADR 0004](0004-one-copy-per-account-and-removal-trusts-the-receipt.md)). Not uploading
again loses the message if it never arrived. Source removal makes this worse, because it must only
follow a copy that is really there.
[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) asked to
reconcile uncertain upload outcomes before retrying, to never remove the source after an uncertain
import, and to keep each message exactly as it was.

How does the service know an upload landed, and how does it find out later whether an upload with an
unknown outcome landed, without trusting headers that spam often reuses?

## Decision Drivers

* No duplicate at the destination, and no lost message.
* Source removal only after the destination shows the copy.
* Spam often reuses Message-IDs, so headers alone can't identify a copy.
* The copy must be the exact bytes Microsoft stored, unread, with the time it reached Outlook.
* Microsoft may serve a message's bytes differently on a second download.
* Settling an unknown upload must not change anything at the destination.

## Considered Options

* Record a hash and size before the upload; verify by UID; settle an unknown upload by comparing the
  recorded hash with the full stored bytes at the recorded destination and folder
* Count an upload as done when the server answers OK
* Match an earlier upload by its Message-ID and Date headers
* Compare against the hash of a fresh download when settling
* Ignore header differences, such as headers a mail scanner adds, when comparing
* SHA-256 as the hash

## Decision Outcome

Chosen option: "Record a hash and size before the upload; verify by UID; settle an unknown upload by
comparing the recorded hash with the full stored bytes at the recorded destination and folder",
because it never counts a copy that the server doesn't show, and never takes another message with
the same headers for the copy.

The rules:

* `GraphSource.mime` downloads the message's `$value`. `Fingerprint.canonical` converts its line
  endings to CRLF with Angus Mail's `CRLFOutputStream`, because IMAP requires CRLF and Angus Mail
  converts on the way out; the hash then covers the bytes the server stores.
* Before the first byte goes out, `Store.savePending` writes a `PENDING` receipt with the
  destination, destination folder, Internet Message-ID, the SHA3-256 hash of the canonical bytes and
  their size.
* `ImapDestination.RawMessage` writes the bytes as they are, because parsing and writing them again
  with `MimeMessage` could reorder or re-encode parts. Its received date is Graph's
  `receivedDateTime`, which becomes the IMAP internal date. `APPEND` sets no flags, so copies arrive
  unread. The service adds no headers.
* With UIDPLUS, the server returns the new UID, and the receipt becomes `IMPORTED` only after the
  folder lists that UID. Without a UID, the service finds the copy by content. If the copy isn't
  visible, the receipt stays `PENDING`.
* On a later poll, a `PENDING` receipt makes `Poller` look at the recorded destination and folder.
  It searches by `Message-ID`, or by `Date` when there is none, or scans the folder. A candidate
  matches only when its size and the SHA3-256 hash of its full stored bytes equal the recorded ones.
  A match marks the receipt `IMPORTED` without uploading. Otherwise the message is uploaded.
* Looking for a copy opens folders read-only, reads with `BODY.PEEK` so nothing becomes read, and
  never creates a missing folder.
* If the recorded destination is down, only that message fails. If it left the configuration, the
  message is copied to the current destination with a warning.
* A `PENDING` receipt blocks source removal until it is settled.

### Consequences

* Good, because a crash between storing and replying leaves no duplicate, on a server that stores
  the bytes it received.
* Good, because a shared Message-ID never counts as a match, so a different spam message with the
  same headers is still copied.
* Good, because normal copying never depends on the content check when the server supports UIDPLUS.
* Bad, because the content check fails when anything between the server and the service changes the
  bytes. A crash at the moment between storing and replying then leaves one duplicate.
* Bad, because settling can mean scanning a whole folder for a message without Message-ID and Date
  headers.

### Confirmation

Tests in `PollerTest`: `anUploadWhoseReplyWasLostIsFoundInsteadOfUploadedAgainAndBlocksRemovalMeanwhile`,
`anEarlierUploadIsFoundByTheRecordedHashEvenWhenMicrosoftServesDifferentBytesLater`,
`aPendingUploadToADestinationNoLongerConfiguredIsCopiedToTheCurrentOne`,
`anUploadThatNeverReachedTheServerIsRetried` and `anEarlierUploadsDestinationBeingDownFailsOnlyThatMessage`.
Tests in `ImapDestinationTest`, against GreenMail: `storesTheMessageByteForByteUnreadWithItsArrivalTimeInANewFolder`,
`canonicalFormUsesCrlfLineEndings`, `findsAStoredMessageByItsContent`,
`aSharedMessageIdWithDifferentContentIsNotAMatch`, `findsMessagesWithoutMessageIdByDateAndWithoutEitherByScanning`
and `lookingForACopyNeverCreatesAFolder`. `Fingerprint.HASH_ALGORITHM` is `SHA3-256`.

## Pros and Cons of the Options

### Record a hash and size before the upload; verify by UID; settle an unknown upload by comparing the recorded hash with the full stored bytes at the recorded destination and folder

The receipt written before the upload holds everything needed to recognise the copy later. Headers
only narrow the search; the full content decides.

* Good, because it identifies the copy by its content, not by headers the sender controls.
* Good, because it works on servers without UIDPLUS, through the content search.
* Bad, because it needs the destination to store and return the exact bytes.

### Count an upload as done when the server answers OK

Mark the receipt imported as soon as `APPEND` returns without error.

* Good, because it needs no second request.
* Bad, because the issue requires that source removal never follows an uncertain import, and an OK
  is not the destination showing the copy.

### Match an earlier upload by its Message-ID and Date headers

Treat any message in the folder with the same Message-ID, or Date, as the earlier upload.

* Good, because it is one IMAP search.
* Bad, because spam often reuses Message-IDs, so a different message would count as the copy and
  the real one would never be uploaded.

### Compare against the hash of a fresh download when settling

Download the message from Graph again and hash it, instead of keeping the hash in the receipt.

* Good, because the receipt would need no hash and size columns.
* Bad, because if Graph serves different bytes the second time, the earlier copy doesn't match and a
  second upload creates a duplicate.

### Ignore header differences, such as headers a mail scanner adds, when comparing

Strip or tolerate headers like `X-Antivirus` before hashing what the server returns.

* Good, because crash recovery would work on machines whose IMAP traffic a scanner inspects.
* Bad, because it weakens the identity check, and it still fails for messages a scanner blocks or
  changes in other ways.

### SHA-256 as the hash

Use SHA-256 for the content hash, as the first design did.

* Good, because it is the more common choice, and the JDK has it built in.
* Neutral, because SHA3-256 is also built into the JDK, and for telling copies apart either is far
  stronger than needed.

## More Information

History, from [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5):

* The first design hashed with SHA-256. The owner asked for SHA3-256 during the review on
  2026-10-26 and gave no further reason. The column was renamed from `sha256` to `content_hash` in
  the unreleased first migration.
* The first version settled an unknown upload by hashing a fresh download. The first review round
  showed that a different second download would create a duplicate; the receipt now keeps the hash
  and size (`Fingerprint.withContent`).
* The first review round also found that a pending receipt whose destination left the
  configuration blocked its folder forever; such a message is now copied to the current destination.
* The second review round made a down recorded destination fail only that message and drop only
  that connection. It also declined to store the search headers in the receipt: the Date header is
  the sender's own, so Microsoft has no reason to change it when serving the bytes again.
* The third review round made the copy search stop creating a missing folder.

The live check in
[outlook-to-imap issue #2](https://github.com/martinfrancois/outlook-to-imap/issues/2) found that
Avast Security for Mac, with "Scan secure connections" on, adds `X-Antivirus` headers to mail read
over IMAP (an 819 byte message came back as 881 bytes), so the content check could not match. With
that setting off, 181 of 181 and 2 of 2 copies came back with exact hash and size, unread, with the
right received times, and two simulated `PENDING` receipts were settled with uploads blocked. The
owner decided in
[outlook-to-imap issue #17](https://github.com/martinfrancois/outlook-to-imap/issues/17) to run the
service where IMAP traffic isn't inspected, keep the fingerprint strict, and document the limit in
the README section "Reliability". On such a machine normal copying still works, because UIDPLUS
verifies each copy by UID; only the recovery of an unknown upload fails, leaving at most one
duplicate per crash.
