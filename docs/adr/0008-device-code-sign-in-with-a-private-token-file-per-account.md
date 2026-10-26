---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26, "Reviewers of [outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5)"]
informed: [Future maintainers and agents working on this repository]
---

# Device code sign-in with a private token file per account

## Context and Problem Statement

The service runs unattended on a server or in a container, with no browser. Each Microsoft account
signs in once and must then stay signed in for as long as the service runs. Microsoft's refresh
tokens live 90 days, and "refresh tokens replace themselves with a fresh token upon every use"
([refresh tokens in the Microsoft identity platform](https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens),
read on 2026-10-26). An existing imapsync-based tool for Outlook.com says personal accounts must
sign in again about every 80 days; the likely cause is that it does not keep the rotated token.

How does an account sign in without a browser on the server, where is the login kept, which
permissions does it ask for, and what keeps it alive without a new sign-in every 90 days? The owner
asked specifically that nobody has to sign in again after 90 days or so.

## Decision Drivers

* Sign-in must work from a headless machine, with the browser part on any other device, as
  [junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) asks.
* Ask Microsoft for the least access that does the job, and never for `Mail.Send`.
* Refresh tokens are secrets: other users of a shared database must not be able to read them.
* A crash while saving must never leave a half-written login.
* Token handling comes from an established library, not hand-written OAuth code.
* A login must not expire on its own while the service runs.

## Considered Options

* Device code flow through MSAL4J, with one private token cache file per account
* Keep the token cache in the database
* Write the device code flow and token refresh by hand
* Ask every account for `Mail.ReadWrite`
* Accept whichever Microsoft account completes the sign-in

## Decision Outcome

Chosen option: "Device code flow through MSAL4J, with one private token cache file per account",
because MSAL4J already implements the device code flow, silent refresh and token rotation, and a
file per account keeps the secrets off a shared database server and survives a crash whole.

The rules that come with it:

* `authorize <account>` runs the device code flow. `MicrosoftLogin` builds one
  `PublicClientApplication` per account with the authority
  `https://login.microsoftonline.com/<tenant>/`, a 10 second connect timeout and a 60 second read
  timeout.
* `TokenCacheFile` is MSAL's `ITokenCacheAccessAspect`. It writes `tokens/<account>.json` under the
  state directory after every cache change, into a directory of mode 700 and a file of mode 600 on
  POSIX file systems. It writes a uniquely named temporary file and renames it over the old one,
  atomically where the file system supports it.
* An account asks for `Mail.Read` and `offline_access`. It asks for `Mail.ReadWrite` instead only
  when one of its folder mappings has source removal on. Nothing asks for `Mail.Send`.
* After the device code flow, the address that signed in must equal the account's configured
  `email`, ignoring case. Otherwise the login is removed from the cache and `authorize` fails.
* `pollInterval` is at most `P30D` (`Config.MAX_POLL_INTERVAL`), so every poll uses the refresh
  token well inside its 90 days, with room for failed polls in between.
* When MSAL needs interaction, or Microsoft answers `invalid_grant`, the account reports that it
  needs a new login (`ReauthorizationRequiredException`) and the other accounts keep running.

### Consequences

* Good, because each refresh saves the rotated token, so a running service never needs a new
  sign-in on a schedule.
* Good, because the token files stay on the local state directory even when the receipts move to a
  shared PostgreSQL server.
* Good, because an account configured for one mailbox can't silently read another.
* Bad, because switching source removal on needs a new `authorize` for that account, since the
  saved login lacks `Mail.ReadWrite`. Until then the account reports "needs a new login".
* Bad, because anyone who can read a token file can read that mailbox until the login is revoked.
  The README says so and tells users to back up and protect the state directory.
* Neutral, because a password change, "Sign out everywhere", removing the app's consent, or more
  than 90 days without a poll still end a login. The README lists these cases.

### Confirmation

Tests in `MicrosoftLoginTest` use WireMock as the Microsoft sign-in endpoints:
`deviceCodeLoginSavesAPrivateTokenFileAndAsksOnlyForReadAccess`,
`refreshesUnattendedAndKeepsTheRotatedRefreshTokenAcrossRestarts`,
`sourceRemovalAsksForWriteAccessButNeverForSending`, `signingInWithAnotherAccountKeepsNothing`,
`switchingRemovalOnWithoutANewLoginAsksForReauthorization`, `aRevokedLoginAsksForReauthorization`
and `anUnreachableLoginServiceIsNotAReauthorization`. `ConfigLoaderTest.rejectsAnIntervalAboveThirtyDays`
checks the interval cap. `Config.graphScopes()` holds the scopes, and AGENTS.md forbids asking for
`Mail.Send`.

Whether Microsoft keeps a login alive past 90 days on a real deployment is tracked in
[outlook-to-imap issue #13](https://github.com/martinfrancois/outlook-to-imap/issues/13).

## Pros and Cons of the Options

### Device code flow through MSAL4J, with one private token cache file per account

MSAL4J signs in with the device code flow, refreshes tokens silently and hands the serialized cache
to `TokenCacheFile`, which keeps one file per account under `tokens/`.

* Good, because MSAL4J implements device code, refresh and rotation, maintained by Microsoft.
* Good, because a small file rewritten whole by rename is safe on NFS, where SQLite is not
  ([ADR 0003](0003-one-database-layer-for-sqlite-and-postgresql.md)).
* Good, because the file can be protected with ordinary file permissions.
* Bad, because the state directory now holds secrets that need a backup and protection.

### Keep the token cache in the database

Store each account's serialized MSAL cache in a table next to the receipts.

* Good, because the database would be the only state to back up.
* Bad, because when the receipts live on a shared PostgreSQL server, its other users and
  administrators could read the refresh tokens.

### Write the device code flow and token refresh by hand

Call Microsoft's OAuth endpoints directly and implement polling for the code, refresh and token
storage in this repository.

* Good, because it would avoid one dependency.
* Bad, because it is protocol code the owner's library rule asks not to write by hand
  ([ADR 0005](0005-graph-sdk-despite-its-size.md)).

### Ask every account for `Mail.ReadWrite`

Request write access for every account, so switching source removal on never needs a new sign-in.

* Good, because the setting could be switched on without running `authorize` again.
* Bad, because accounts that never remove anything would grant write access for nothing, which the
  issue rules out: it asks for `Mail.ReadWrite` only when source removal is explicitly enabled.

### Accept whichever Microsoft account completes the sign-in

Save the login of whoever signs in on the device code page, whatever the configured `email` says.

* Good, because it needs no check.
* Bad, because one wrong click on the sign-in page ties an account's configuration, receipts and
  destination folders to the wrong mailbox, with nothing to show it happened.

## More Information

Choices with no further recorded reason: the 1 minute floor of `pollInterval`
(`Config.MIN_POLL_INTERVAL`) and the 10 and 60 second MSAL timeouts. The 1 hour default interval
comes from [junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66).

The unique temporary file name came from the first review round on
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5): a dry
run or `authorize` next to the running service can refresh the same login at the same time, and a
fixed `<account>.json.tmp` name would let the two writes collide. The same review listed the email
check as scope the issue did not ask for; it was kept as a safety check.

The live check in
[outlook-to-imap issue #2](https://github.com/martinfrancois/outlook-to-imap/issues/2) signed two
personal accounts in with `Mail.Read`, and showed that switching removal on for one mapping made that
account fail with "needs a new login" while the other kept running.

Work and school accounts use the same flow with another tenant, see
[ADR 0009](0009-work-and-school-accounts-best-effort.md). The app registration the flow signs in to
is in [ADR 0001](0001-each-user-registers-their-own-app.md).
