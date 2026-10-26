---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [Claude Code agent session of 2026-10-26]
informed: [Future maintainers and agents working on this repository]
---

# IMAP destinations with a password over TLS

## Context and Problem Statement

The service uploads copies into folders on IMAP servers.
[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) named IMAP
`APPEND` as the provider-neutral way in, and allowed provider-specific import APIs as optional
adapters. Providers differ in how they let a program log in: Fastmail, Gmail and iCloud Mail accept
app passwords, a self-hosted Dovecot accepts the mailbox password, and some servers, Outlook.com
among them, accept only OAuth.

Which destinations does the service support, and how does it log in to them?

## Decision Drivers

* One code path that works for most IMAP servers.
* No password ever sent in plain text.
* Passwords must not live in the configuration file.
* The issue's case, Fastmail, must work.

## Considered Options

* IMAP with a username and a password or app password, over TLS or STARTTLS only
* Also support OAuth (XOAUTH2) logins to IMAP servers
* Provider-specific import APIs as adapters

## Decision Outcome

Chosen option: "IMAP with a username and a password or app password, over TLS or STARTTLS only",
because Fastmail, Gmail, iCloud Mail and self-hosted servers such as Dovecot all accept this kind
of login, so one code path serves them without an extra sign-in flow. Of these, only Fastmail was
used in the live check.

The rules:

* `destinations` is a named map. Each entry has a host, an optional port, `security` and a username,
  and reads its password from `passwordFile`. `SecretFile` strips surrounding whitespace.
* `Config.Security` has only `TLS` (default port 993) and `STARTTLS` (default port 143). With
  STARTTLS, `mail.imap.starttls.required` is set, so the login never happens without TLS.
  `mail.imap.ssl.checkserveridentity` is on.
* Copies arrive with `APPEND`, which is not a delivery. The destination's spam filters, sieve rules
  and forwarding never see them. The README advises an ordinary review folder rather than the
  destination's spam folder.

### Consequences

* Good, because any IMAP server with password logins works, without code for each provider.
* Good, because there is no setting that sends a password in plain text.
* Bad, because servers that accept only OAuth, including Outlook.com, can't be destinations.
* Neutral, because copies skip the destination's incoming-mail rules, which the README states.

### Confirmation

`ImapDestinationTest.refusesToLogInWithoutTls` and `aWrongPasswordIsReportedAsUnavailable`.
`PollerTest.theSameConfigurationWorksForTwoDifferentImapServers` runs one configuration against two
GreenMail servers. `Config.Security` has no plain-text value. The README section "Destinations"
lists the supported login types.

## Pros and Cons of the Options

### IMAP with a username and a password or app password, over TLS or STARTTLS only

Angus Mail logs in with the username and the password read from a file, over TLS from the first
byte or after a required STARTTLS.

* Good, because one implementation serves every server that accepts password logins.
* Good, because an app password can be revoked at the provider without changing the account's
  main password.
* Bad, because a leaked password file gives full IMAP access to that mailbox until it is revoked.

### Also support OAuth (XOAUTH2) logins to IMAP servers

Sign in to destinations with OAuth, as some providers require.

* Good, because Outlook.com and other OAuth-only servers could be destinations.
* Bad, because each provider needs its own app registration and sign-in flow, a second copy of what
  [ADR 0008](0008-device-code-sign-in-with-a-private-token-file-per-account.md) does for Microsoft.

### Provider-specific import APIs as adapters

Use a provider's own import API, such as one for Gmail, instead of IMAP for that provider.

* Good, because an API may offer features IMAP lacks.
* Bad, because each adapter is code that only helps one provider, and IMAP `APPEND` already works
  for all of them.

## More Information

The agent proposed password logins over TLS on 2026-10-26, noting that Outlook.com as a destination
needs OAuth and so is out, and the owner confirmed it as part of the plan. The session recorded no
further reason for leaving OAuth destinations out. No provider-specific adapter was built.

The issue's acceptance criteria asked for Fastmail and at least one other IMAP destination. The
tests run against GreenMail; the live check in
[outlook-to-imap issue #2](https://github.com/martinfrancois/outlook-to-imap/issues/2) used
Fastmail. Whether a destination stores messages unchanged matters for crash recovery, see
[ADR 0014](0014-verify-each-upload-and-settle-unknown-ones-by-content.md).
