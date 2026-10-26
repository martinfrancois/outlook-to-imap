---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository]
---

# A new repository named outlook-to-imap

## Context and Problem Statement

The service started as
[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66): replace
junkEmailMover's IMAP-and-SMTP forwarding with a Graph-based import. Once the design grew to copy any
folder, the old name described one configuration example, not the tool. The owner asked for
nothing of the old code to remain and for no backward compatibility, and then asked whether the
project should get a new name and a new repository.

Where does the new code live, what is it called, and under which licence?

## Decision Drivers

* The name should say what the tool does in the words people search for.
* Microsoft's trademark guidelines say a product name must be free of Microsoft's brand assets.
* A rename request must stay survivable.
* Nothing from junkEmailMover carries over, so its history buys little.
* junkEmailMover had no licence, and the new repository is public.

## Considered Options

* A new repository named `outlook-to-imap`, with junkEmailMover archived
* Rename junkEmailMover in place
* Name it `graph-to-imap`
* Name it `outlook-imap-import`
* Name it `imapferry`

## Decision Outcome

Chosen option: "A new repository named `outlook-to-imap`, with junkEmailMover archived", because the
owner judged that a rename would change the old repository too much, and `outlook-to-imap` names the
source and the destination in the words someone looking for this tool types. Large projects have
used Microsoft product names for years without being renamed, and the cost of a rename request
would be a new image path.

What comes with it:

* The repository is public, under the MIT licence. Its "Required merge checks" ruleset was copied
  by hand from junkEmailMover; what it enforces is in
  [ADR 0012](0012-releases-with-release-please-after-a-live-check.md).
* The README and the repository description say "Not affiliated with Microsoft". The README names
  Outlook, Hotmail and Microsoft 365 as Microsoft's trademarks.
* The README avoids the word "sync" for what the tool does, because it copies one way and never
  deletes at the destination. The config key `removeSourceAfterSync` keeps the word, because the
  issue named it.
* [junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) and
  [junkEmailMover issue #48](https://github.com/martinfrancois/junkEmailMover/issues/48) were closed
  by hand with a link, since a pull request can't close an issue in another repository.
  junkEmailMover was archived with the description "Replaced by" and a link to this repository.
* The README section "Moving from junkEmailMover" tells users to stop the old job, delete its stored
  passwords and revoke its app passwords. Nothing carries over.

### Consequences

* Good, because the repository starts with only the new code and a matching history.
* Good, because the name finds the people who need the tool.
* Bad, because Microsoft could ask for a rename. GitHub redirects a renamed repository, but GHCR
  does not redirect images, so every user would have to change the image path.
* Bad, because the ruleset and the Renovate enrollment had to be set up again by hand, and the old
  issues stay in an archived repository.
* Neutral, because junkEmailMover's README could not be updated after archiving; its description
  points here.

### Confirmation

`gh repo view martinfrancois/junkEmailMover --json isArchived` shows `true`. The first lines of the
README and the repository description contain "Not affiliated with Microsoft". `LICENSE` is the MIT
licence. The Java package is `ch.fmartin.outlooktoimap` and the image
`ghcr.io/martinfrancois/outlook-to-imap`.

## Pros and Cons of the Options

### A new repository named `outlook-to-imap`, with junkEmailMover archived

Create `martinfrancois/outlook-to-imap`, build the service there, and archive junkEmailMover.

* Good, because the name says where mail comes from and where it goes, and "to" says one direction.
* Good, because the new repository holds no old code or history.
* Bad, because the name uses a Microsoft trademark, which the guidelines object to.
* Bad, because settings, Renovate and the issue trail don't move along.

### Rename junkEmailMover in place

Rename the old repository and replace its code in one pull request. The agent recommended this.

* Good, because GitHub redirects the old URLs, and the issues, the ruleset, the Renovate install and
  the two forks would carry over.
* Bad, because the owner felt it would change the old repository too much.

### Name it `graph-to-imap`

Name the tool after Microsoft Graph, the API it reads through.

* Good, because "Graph" is an ordinary word and carries little trademark risk.
* Bad, because "Graph" only means something to people who know the Microsoft Graph API.

### Name it `outlook-imap-import`

Use "import", the issue's own word for what happens on the IMAP side.

* Good, because it matches the issue's wording.
* Bad, because it has the same trademark problem as `outlook-to-imap` and is longer.

### Name it `imapferry`

An invented word: it ferries mail to IMAP.

* Good, because it contains no Microsoft word at all.
* Bad, because nobody can guess from the name what it does or where the mail comes from.

## More Information

Microsoft's [trademark guidelines](https://www.microsoft.com/en-us/legal/intellectualproperty/trademarks),
read on 2026-10-26, say not to use Microsoft's brand assets in the name of a product or app, and
that an app's name must be "free of Microsoft's Brand Assets". They allow a truthful statement of
compatibility in the description. Precedent checked the same day: abraunegg/onedrive (12,936 stars,
in use since 2018, packaged by Debian and Ubuntu), phw198/OutlookGoogleCalendarSync (2,148 stars,
since 2017) and aluxnimm/outlookcaldavsynchronizer (1,095 stars, since 2015). None was renamed.
Whether any of them was ever asked privately is unknown.

The owner chose a new repository over renaming junkEmailMover in place on 2026-10-26, because the
rename "would change it too much". The owner then chose the name `outlook-to-imap`, after asking
whether a version without trademark issues existed and whether well-known tools with Microsoft names
had been asked to rename. The agent's recommendation
moved from `outlook-to-imap` to `graph-to-imap` after reading the guidelines, and back after the
precedent check. junkEmailMover was archived on 2026-10-26 at the owner's request.

The MIT licence was the agent's proposal, matching what the owner used for other tools that had no
licence before, and was part of the plan the owner confirmed. The code shipped with
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5);
[junkEmailMover issue #48](https://github.com/martinfrancois/junkEmailMover/issues/48) is discussed
in [ADR 0013](0013-dependency-updates-and-transitive-pins.md).
