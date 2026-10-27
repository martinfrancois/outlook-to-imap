---
status: accepted
date: 2026-10-27
decision-makers: ["implementing coding agent, after martinfrancois asked for [outlook-to-imap issue #25](https://github.com/martinfrancois/outlook-to-imap/issues/25) to be fixed"]
consulted: []
informed: [Future maintainers and agents working on this repository]
---

# Reread a folder whose saved delta link answers 404

## Context and Problem Statement

Graph answers 404 for a missing folder as it does for a missing message.
[outlook-to-imap issue #25](https://github.com/martinfrancois/outlook-to-imap/issues/25) asked that
a 404 on a folder name the configured source instead of reporting "message no longer exists", and
[ADR 0010](0010-read-any-source-folder-through-graph-delta-queries.md) now raises
`FolderNotFoundException` for a 404 on a folder request.

One request is different. When a folder mapping has a checkpoint, the poll's first delta request
follows the saved delta link, and that link belongs to the folder it was made for. `Poller`
resolves the configured name to a folder id on every poll, but the saved link does not use that id.

Deleting a folder in Outlook moves it to Deleted Items, and a folder keeps its id when it moves
([Microsoft: immutable identifiers](https://learn.microsoft.com/en-us/graph/outlook-immutable-id):
"Container types (mailFolder, calendar, etc.) don't support immutable ID, but their regular IDs
were already constant"). The saved link of a deleted folder therefore keeps answering 200. A 404 on
the saved link means the folder it belongs to is gone for good, for example emptied out of Deleted
Items. What should the poll do then?

## Decision Drivers

* The checkpoint of a folder that is gone for good can never work again.
* A folder mapping should not fail on every poll while its configured name finds a folder.
* Nobody should have to delete a checkpoint by hand.
* A source that finds no folder must still fail with an error that names it, as
  [outlook-to-imap issue #25](https://github.com/martinfrancois/outlook-to-imap/issues/25) asked.

## Considered Options

* Reread the folder from the start, as for an expired delta link
* Report the folder as gone at once
* Compare the folder id inside the saved link with the resolved one

## Decision Outcome

Chosen option: "Reread the folder from the start, as for an expired delta link", because the
request without a link uses the folder id resolved from the configured name in this poll. It reads
whatever folder has that name now, or fails with the folder-gone error if Graph has none.

`GraphSource.changes` turns a 404 on the first request with a saved link into
`DeltaExpiredException`, and `Poller.copyFolder` asks again without a link, as it does for an
expired link. A 404 on that request, or on any later page, raises `FolderNotFoundException`.

### Consequences

* Good, because a checkpoint whose folder is gone for good is replaced by the next round that
  succeeds, with no manual step.
* Good, because the receipts skip every message already copied, so the reread copies nothing twice.
* Bad, because a source that finds no folder costs one extra request before the mapping fails.
* Neutral, because a folder deleted and created again under the same name is not covered here: the
  old folder keeps its id in Deleted Items and its saved link answers 200.
  [ADR 0027](0027-a-checkpoint-remembers-the-folder-its-delta-link-reads.md) covers it.

### Confirmation

`GraphSourceTest.reportsADeltaLinkToAFolderThatIsGoneAsExpired` checks that a 404 on a saved link
raises `DeltaExpiredException`. `PollerTest.aSavedDeltaLinkThatAnswers404RereadsTheFolderTheNameNowFinds`
checks that the poll then reads the folder the name finds now and saves its delta link.
`PollerTest.aSourceFolderThatIsGoneBehindASavedDeltaLinkIsRereadThenNamedInTheAccountError` checks
that when the request without a link answers 404 too, both requests were sent and the mapping fails
with the folder-gone error.

## Pros and Cons of the Options

### Reread the folder from the start, as for an expired delta link

Treat a 404 on a saved link like a 410: drop the link for this round and list the folder the name
resolves to from the start.

* Good, because the result comes from the folder the configured name finds now.
* Good, because it reuses the path that already handles an expired link.
* Bad, because a source that finds no folder costs one more request.

### Report the folder as gone at once

Raise `FolderNotFoundException` for the 404 on the saved link, as for any other folder request.

* Good, because it needs no extra request.
* Bad, because the mapping would fail on every poll, even when the configured name finds a folder
  again, until someone deleted the checkpoint by hand.

### Compare the folder id inside the saved link with the resolved one

Read the folder id out of the saved link and reread the folder when it differs from the id the
name resolves to.

* Good, because it would also notice a folder deleted and created again, before any 404.
* Bad, because Microsoft says to follow the link without inspecting its contents
  ([delta query overview](https://learn.microsoft.com/en-us/graph/delta-query-overview#state-tokens)),
  so its form can change without notice.

## More Information

The first draft of this choice in
[outlook-to-imap pull request #32](https://github.com/martinfrancois/outlook-to-imap/pull/32)
explained a 404 on the saved link as a folder deleted and created again under the same name. Review
found that wrong, because such a folder keeps its id and its saved link keeps answering 200.
[ADR 0027](0027-a-checkpoint-remembers-the-folder-its-delta-link-reads.md) stores the resolved
folder id with the checkpoint for that case. The reread stays useful next to it, because a
checkpoint whose folder is gone for good still answers 404.
