---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository]
---

# Read any source folder through Graph delta queries

## Context and Problem Statement

junkEmailMover read Outlook's Junk folder over IMAP with a stored password, and could forward each
message through SMTP with the source account as sender. That sends spam out through the user's
Microsoft account and risks its sending limits and outbound filters
([junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66)). Fastmail's
own fetch reads only the Inbox. The issue asked for a replacement that reads Junk through Microsoft
Graph and imports it into an IMAP folder.

During the design, the owner asked whether letting people choose any source folder, not only Junk,
would add much complexity. The question for this record: how does the service read a source
account, which folders can it read, and how does it know what is new since the last poll?

## Decision Drivers

* Read-only access to the source account, and nothing ever sent.
* A message copied once must never be copied again, even after the user deletes the copy at the
  destination. Tools that compare source and destination on each run can't promise that.
* A later poll should ask only for what changed, so a big folder costs few requests.
* A message moved between two read folders must stay recognisable as the same message.
* What gets copied should be visible in the configuration.
* The Junk-to-Fastmail case stays the README's lead example.

## Considered Options

* Build a Graph reader: delta queries per folder mapping, immutable ids, any folder listed by name
* Run an existing IMAP sync tool such as imapsync with an OAuth proxy
* Use Fastmail's fetch or one-time import
* Keep reading Junk only
* Copy a folder together with all its subfolders

## Decision Outcome

Chosen option: "Build a Graph reader: delta queries per folder mapping, immutable ids, any folder
listed by name", because it is the only option that reads with `Mail.Read` alone, remembers each
message by an id that survives moves, and copies only what changed since the last poll.

The rules:

* `GraphSource` asks for every message with the header `Prefer: IdType="ImmutableId"`, added by an
  OkHttp interceptor (`IMMUTABLE_IDS`). Without it, Graph changes a message's id when it moves
  between folders, and a moved message would look new.
* A source is either one of Graph's 17 well-known folder names (`WELL_KNOWN_FOLDERS`, such as
  `junkemail`, `inbox` or `archive`) or a path of display names separated by `/`, resolved one level
  at a time, ignoring case. A well-known name works whatever language Outlook shows its folders in.
* Subfolders are not included. Each folder to copy is its own folder mapping.
* `GraphSource.changes` pages `me/mailFolders/<id>/messages/delta` with the SDK's `PageIterator`,
  100 messages per page, asking only for `id`, `internetMessageId` and `receivedDateTime` on the
  first round. Entries marked `@removed` are ignored, because copying never deletes.
* Without a saved delta link the round lists the whole folder, which is how the first poll copies
  the backlog. A 410, `SyncStateNotFound` or `resyncRequired` reply raises `DeltaExpiredException`
  and the folder is read again from the start; receipts skip what was already copied.
* The delta link is saved as the checkpoint of the account and source folder only when the round
  had no failed message, was not interrupted and was not a dry run. A failed message is therefore
  seen again on the next poll.

### Consequences

* Good, because the service needs no IMAP access to Outlook, only the read-only Graph permission.
* Good, because one design covers Junk, Inbox, Archive or any custom folder.
* Good, because a moved message keeps its receipt, which
  [ADR 0004](0004-one-copy-per-account-and-removal-trusts-the-receipt.md) relies on.
* Bad, because one failed message makes the next poll read the whole delta round of that folder
  again. Receipts skip the rest, at the cost of a few listing requests.
* Bad, because a subfolder created later in Outlook is not copied until someone adds a mapping.
* Neutral, because an unknown folder path fails that mapping only, with `FolderNotFoundException`.
  A folder that disappears between polls is currently reported with the wrong message, see
  [outlook-to-imap issue #25](https://github.com/martinfrancois/outlook-to-imap/issues/25).

### Confirmation

`GraphSourceTest` runs against WireMock as Graph:
`asksForImmutableIdsAndLeavesOutMessagesThatLeftTheFolder`,
`pagesThroughTheWholeFolderAndReturnsTheDeltaLink`, `reportsAnExpiredDeltaLink`,
`resolvesWellKnownNamesWithoutAsking` and `resolvesAFolderPathByDisplayNameAcrossPages`.
`PollerTest.oneBadMessageDoesNotStopTheOthersAndIsRetriedNextPoll` checks the checkpoint rule and
`StoreTest.checkpointsArePerAccountAndFolder` the checkpoint key. There is no SMTP code in the
repository.

## Pros and Cons of the Options

### Build a Graph reader: delta queries per folder mapping, immutable ids, any folder listed by name

A new service reads each configured folder through Graph delta queries and keeps its own receipt
per message and its own checkpoint per folder.

* Good, because `Mail.Read` is enough for copying.
* Good, because the receipt, not a comparison with the destination, decides what is new.
* Good, because a crash during an upload can be settled without a duplicate
  ([ADR 0014](0014-verify-each-upload-and-settle-unknown-ones-by-content.md)).
* Bad, because it is a new service to maintain instead of an existing tool.

### Run an existing IMAP sync tool such as imapsync with an OAuth proxy

Run imapsync, mbsync or offlineimap against Outlook.com, with OAuth handled by a tool such as
email-oauth2-proxy or a ready-made container that combines them.

* Good, because the tools exist and are mature.
* Bad, because Microsoft's IMAP scope is read and write, so there is no read-only option.
* Bad, because imapsync decides what to copy by comparing source and destination on each run, so a
  copy deleted at Fastmail can come back, which is the case the owner asked to rule out.
* Bad, because the closest ready-made container says personal accounts must sign in again about
  every 80 days.

### Use Fastmail's fetch or one-time import

Let Fastmail pull the mail itself.

* Good, because nothing needs to run.
* Bad, because the fetch reads only the Inbox, not Junk, and the import runs once, not on a
  schedule.

### Keep reading Junk only

Read the `junkemail` folder and nothing else, as the issue described.

* Good, because the configuration would be simpler.
* Bad, because the same code can serve any folder, and the owner wanted the tool to be useful
  beyond the one case.

### Copy a folder together with all its subfolders

A mapping for `Inbox` would also copy every folder below it.

* Good, because a new subfolder would be picked up without a configuration change.
* Bad, because what gets copied would no longer be visible in the configuration file.

## More Information

The agent compared existing tools on 2026-10-26 before the owner decided to build a new one. Graph
based MCP servers and clients, such as outlook-mcp, read Outlook mail but copy nothing to IMAP.

The owner asked about any source folder on 2026-10-26, and asked that the README lead with the Junk
example. The agent answered that the change was small; the per-folder checkpoints and immutable ids
came with it. The first design keyed receipts per folder mapping; that changed to one receipt per
account, see [ADR 0004](0004-one-copy-per-account-and-removal-trusts-the-receipt.md).

How the service waits when Graph throttles these requests is in
[ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md). The service shipped with
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5).
