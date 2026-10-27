---
status: accepted
date: 2026-10-27
decision-makers: ["implementing coding agent, after martinfrancois asked for [outlook-to-imap issue #34](https://github.com/martinfrancois/outlook-to-imap/issues/34) to be fixed"]
consulted: []
informed: [Future maintainers and agents working on this repository]
---

# A checkpoint remembers the folder its delta link reads

## Context and Problem Statement

`Poller` resolves the configured source name to a folder id on every poll, but when a checkpoint
exists the first delta request follows the saved delta link, and that link reads the folder it was
made for. Until migration V2 the checkpoint stored no folder id.

Deleting a folder in Outlook moves it to Deleted Items, and a folder keeps its id when it moves
([Microsoft: immutable identifiers](https://learn.microsoft.com/en-us/graph/outlook-immutable-id):
"Container types (mailFolder, calendar, etc.) don't support immutable ID, but their regular IDs
were already constant"). Its saved link keeps answering 200. When the user then creates a folder
with the same name, or renames another folder to it, the poll keeps reading the old folder in
Deleted Items: new mail in the new folder is never copied, and with removal on the service tries to
move messages that are already in Deleted Items.
[ADR 0026](0026-reread-a-folder-whose-saved-delta-link-answers-404.md) covers only a link that
answers 404. How does the poll notice that the link reads another folder than the name finds?

## Decision Drivers

* New mail in the folder the configured name finds must be copied.
* Microsoft says to follow a delta link without inspecting its contents
  ([delta query overview](https://learn.microsoft.com/en-us/graph/delta-query-overview#state-tokens)).
* The released migration V1 stays as it is; schema changes are new Flyway migrations.
* A checkpoint saved by 1.x must not keep reading a replaced folder after the upgrade.
* No extra Graph request on every poll.

## Considered Options

* Store the resolved folder id with the checkpoint
* Compare the folder id inside the saved link with the resolved one
* Check on every poll whether the checkpoint's folder sits in Deleted Items
* Key the checkpoint by folder id instead of the configured name

## Decision Outcome

Chosen option: "Store the resolved folder id with the checkpoint", because it notices every case in
which the name finds another folder, with no extra request and without reading the link.

Migration `V2__checkpoint_folder_id.sql` adds the nullable column `checkpoint.folder_id`.
`Poller.copyFolder` saves the id it resolved for the round with the delta link. It drops the saved
link and reads the folder from the start when `Store.Checkpoint.readsFolder` says the stored id is
missing or not the one resolved now.

This deviates from [outlook-to-imap issue #34](https://github.com/martinfrancois/outlook-to-imap/issues/34),
which said a checkpoint from 1.x without a folder id keeps its link and gets the id on its next
save. If such a folder had already been replaced before the upgrade, that next save would store the
new folder's id next to the old folder's link, and the mismatch would never show again. A checkpoint
without a folder id is therefore treated as reading an unknown folder, and its folder is read from
the start once.

### Consequences

* Good, because a folder deleted and created again under the same name, or a name that now finds
  another folder, is read from the start and its new mail is copied.
* Good, because removal no longer reaches for messages of a folder that sits in Deleted Items.
* Good, because the receipts skip every message already copied, so the reread copies nothing twice.
* Bad, because the first poll after upgrading from 1.x lists every source folder once more. That is
  the same listing the first poll made, and it uploads nothing that already has a receipt.
* Neutral, because messages that reached the old folder after the last poll and before the user
  deleted it are not copied. They moved to Deleted Items with the folder.

### Confirmation

`PollerTest.aFolderDeletedAndCreatedAgainUnderTheSameNameIsReadFromTheStart` checks that the poll
reads the new folder, never asks the old link again and saves the new id.
`PollerTest.aCheckpointWithoutAFolderIdRereadsTheFolderOnceThenFollowsItsDeltaLinks` checks the
upgrade path, with the old link still answering.
`StoreTest.aCheckpointSavedBeforeFolderIdsReadsNoKnownFolderUntilItsNextSave` runs migration V1,
saves a checkpoint the way 1.x did and opens the store on SQLite and PostgreSQL.

## Pros and Cons of the Options

### Store the resolved folder id with the checkpoint

Add a nullable `folder_id` column to `checkpoint`, save the resolved id with every delta link, and
reread the folder from the start when the id differs or is missing.

* Good, because it covers a deleted folder, a renamed folder and a configuration that now points
  elsewhere.
* Good, because it needs no extra request.
* Bad, because it adds a migration, and 1.x checkpoints cost one full listing.

### Compare the folder id inside the saved link with the resolved one

Read the folder id out of the saved link and reread when it differs.

* Good, because it needs no schema change.
* Bad, because Microsoft says not to inspect the link, so its form can change without notice.
  [ADR 0026](0026-reread-a-folder-whose-saved-delta-link-answers-404.md) rejected it for the same
  reason.

### Check on every poll whether the checkpoint's folder sits in Deleted Items

Before following the link, ask Graph for the folder's parent and reread when it is Deleted Items.

* Good, because it explains the reread in the log.
* Bad, because it needs the old folder id anyway, which the checkpoint does not have without a
  schema change.
* Bad, because it costs one request per folder mapping and poll.
* Bad, because it misses a folder that was renamed away and replaced, which never went to Deleted
  Items.

### Key the checkpoint by folder id instead of the configured name

Make the folder id part of the checkpoint's primary key, so a new folder starts without a
checkpoint.

* Good, because a new folder starts fresh without any comparison.
* Bad, because it rewrites the primary key of a released table on both engines.
* Bad, because every replaced folder leaves an orphaned row behind.
