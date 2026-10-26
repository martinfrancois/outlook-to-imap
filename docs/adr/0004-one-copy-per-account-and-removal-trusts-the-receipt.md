---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26, "Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# One copy per account, and source removal trusts the receipt

## Context and Problem Statement

The service copies one way, from Microsoft folders to IMAP folders, and keeps a receipt for every
message it copied. The owner set two requirements. First, a message copied once must never be
copied again, even after the user deletes the copy at the destination: in the Junk-to-Fastmail
case, a spam message deleted at Fastmail must not come back. Second, the service is a copier, not
a sync. It never deletes or changes anything at a destination because the source changed.

Three questions follow. What is a receipt keyed by, so that a message moved in Outlook between two
copied folders stays one message? What does the service consult before copying: its receipts, the
destination, or both? And when `removeSourceAfterSync` is switched on for a folder that ran without
it, which source messages leave the source?

## Decision Drivers

* A message deleted at the destination must stay deleted.
* The service never deletes at a destination, so it can't clean up duplicates it creates.
* Source removal must never lose mail: it only moves to Deleted Items, and only after a verified
  copy.
* A user who deletes spam at Fastmail and then switches removal on expects it gone from Outlook too.
* Each check against the destination costs IMAP requests and code.

## Considered Options

* One receipt per source account and immutable message id; switching removal on removes every
  receipted message still in the source
* One receipt per folder mapping and message id
* Switching removal on removes earlier copies only after checking each copy still exists
* Switching removal on removes only messages copied from then on
* Keep the destination in step with the source

## Decision Outcome

Chosen option: "One receipt per source account and immutable message id; switching removal on
removes every receipted message still in the source", because it is the only combination in which
the destination never gets a duplicate and a copy the user deleted never comes back, and removal
does what a user who deleted spam at the destination expects.

The rules:

* A receipt's key is the account's configured `name` and the message's immutable Graph id
  ([ADR 0010](0010-read-any-source-folder-through-graph-delta-queries.md)). A message moved in
  Outlook from one copied folder to another keeps its id, so it is copied once, by whichever mapping
  sees it first. Its copy stays in the destination folder of that first mapping.
* The receipt is the only memory. A message with a receipt is never uploaded again, whatever
  happens at the destination. The destination is only searched to settle an upload whose outcome is
  unknown ([ADR 0014](0014-verify-each-upload-and-settle-unknown-ones-by-content.md)).
* A receipt is `PENDING` from just before the upload, `IMPORTED` once the destination lists the
  copy, and `REMOVED` once the source message is in Deleted Items.
* `removeSourceAfterSync` is set per folder mapping and is `false` unless set. When it is on,
  `Poller` moves a message to Deleted Items only when its receipt is `IMPORTED`. A 404 on the move
  counts as already gone. A failed move leaves the receipt `IMPORTED`, and the next poll tries again
  without a second upload. Nothing empties Deleted Items, marks a message as not junk, or moves it to
  the Inbox.
* Each checkpoint records whether removal was on (`removal_enabled`). When a mapping has removal on
  and its checkpoint says it was off, the poll drops the delta link and reads the whole folder, so
  every message with a receipt still in the source moves to Deleted Items, whether or not its copy
  still exists.
* Nothing at a destination is ever changed or deleted. The one write besides uploads is creating a
  missing destination folder, which is logged.
* `once --dry-run` lists what a poll would copy and remove. It writes no receipts or checkpoints,
  but it signs in, which saves the refreshed login, and it creates the database on first use.

### Consequences

* Good, because a copy deleted at the destination never comes back, not even when the original is
  moved to another copied folder.
* Good, because removal needs no request to the destination, and switching it on cleans up the
  backlog in one poll.
* Good, because Deleted Items keeps every removed message recoverable in Outlook.
* Bad, because a destination folder shows where a message was when it was first copied, not where it
  is now in Outlook.
* Bad, because switching removal on also removes messages whose copy the user has since deleted at
  the destination. `once --dry-run` shows that list first.
* Bad, because losing the database loses the memory: the next poll copies everything still in the
  source again. The README tells users to back up the database with the token files.
* Bad, because renaming an account in the configuration starts a new set of receipts, so its
  backlog is copied again. The README says so next to `accounts[].name`.

### Confirmation

Tests in `PollerTest`: `copiesTheBacklogOnceAndNeverAgainEvenAfterTheCopiesAreDeleted` (both
database engines), `aMessageMovedBetweenTwoSyncedFoldersIsCopiedOnce`,
`withRemovalOffTheSourceIsNeverTouched`, `withRemovalOnTheSourceMovesToDeletedItemsAfterTheVerifiedImport`,
`switchingRemovalOnRemovesWhatWasCopiedBeforeWithoutCopyingItAgain`,
`aFailedRemovalIsRetriedAfterARestartWithoutASecondCopy`,
`aRemovalWhoseReplyWasLostCopiesNothingAgainOnceTheMessageIsGone`, `aSourceAlreadyGoneCountsAsRemoved`
and `aDryRunListsThePlanAndChangesNothing`. `StoreTest.aReceiptGoesFromPendingToImportedToRemovedAndSurvivesAReopen`
checks the states, and `GraphSourceTest.movesToDeletedItems` the move. The receipt table's primary
key is `(account, message_id)` in `V1__receipts_checkpoints_status.sql`. AGENTS.md forbids code that
deletes on a destination or uploads a message that has a receipt.

## Pros and Cons of the Options

### One receipt per source account and immutable message id; switching removal on removes every receipted message still in the source

The receipt table is keyed by account and immutable id. Removal acts on the receipt alone, and
switching it on rereads the folder once.

* Good, because each message reaches the destination exactly once.
* Good, because removal costs one Graph request per message and nothing at the destination.
* Bad, because the destination's folder layout drifts from Outlook's when the user moves mail.

### One receipt per folder mapping and message id

The receipt key adds the folder mapping, which was the first design in the session. A message moved
from Inbox to Archive in Outlook is copied again into the Archive mapping's destination folder.

* Good, because each destination folder holds everything that is in its source folder.
* Bad, because a move in Outlook puts a second copy at the destination, and the service can't
  delete the first one.
* Bad, because a copy the user deleted at the destination comes back in another folder after the
  original is archived in Outlook.

### Switching removal on removes earlier copies only after checking each copy still exists

Before each move to Deleted Items, search the destination by Message-ID and content. A message
whose copy is gone stays in Outlook and is counted as "kept, copy missing". The agent recommended
this option.

* Good, because the service would never remove a message whose only other copy is gone.
* Bad, because it keeps exactly the messages the user deleted at the destination as spam.
* Bad, because it costs an IMAP search for every message.

### Switching removal on removes only messages copied from then on

Record when removal was switched on, and leave every earlier message in the source for good.

* Good, because switching removal on could never surprise anyone with a mass move.
* Bad, because old mail would never leave the source, so switching removal on would not clean up a
  folder that ran with it off.

### Keep the destination in step with the source

Delete or move copies at the destination when the source message is deleted or moved, as a sync
tool does.

* Good, because the destination would mirror Outlook.
* Bad, because the owner ruled it out: the tool copies what is missing at the destination and never
  removes mail there.
* Bad, because a message Outlook's Junk folder purges after a few days would vanish from the
  destination too, which defeats the Junk-to-Fastmail case.

## More Information

The owner asked on 2026-10-26 that a message copied once never be copied back after it was deleted
at Fastmail. The owner chose one copy per account (answer A to the question about moved messages),
which reversed the first design's per-mapping receipts. For switching removal on, the agent offered
three options and recommended checking each copy first. The owner chose to trust the receipt,
because someone who deleted a message at Fastmail as spam wants it gone from the source too.

The `once --dry-run` preview was proposed with the removal question and kept with the chosen
option. Its README wording was corrected in the first review round on
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5): it
had said a dry run writes nothing, but the token refresh rewrites the token file and Flyway creates
the database. The same review found that the README promised nothing at the destination is ever
changed while folders get created; the wording now says "Apart from creating a missing destination
folder". The second review round pointed out that renaming an account copies its backlog again;
that was documented in the README rather than changing the key. The session recorded no reason for
keying receipts by the account's `name` rather than its `email`.

The removal rules come from
[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66): off by
default, a recoverable move to Deleted Items, never after an uncertain import, and failed removals
retried without importing again. Making the setting per folder mapping instead of per account came
with reading any folder. The config key keeps the word "sync" because the issue named it that way.

The live check in
[outlook-to-imap issue #2](https://github.com/martinfrancois/outlook-to-imap/issues/2) deleted one
copy at the destination; two further polls imported nothing and did not recreate it. Removal on a
disposable folder moved one message to Deleted Items, and a repeat poll changed nothing.
