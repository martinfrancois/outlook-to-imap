---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository]
---

# Each user registers their own Microsoft app

## Context and Problem Statement

Microsoft Graph hands out a source account's mail only to an app registration that the account's
owner signed in to. The device code sign-in needs that registration's client ID. Since June 2024,
every new registration has to live in a Microsoft Entra directory, and a personal Microsoft account
has none by default. A person with only a Hotmail or Outlook.com account has to create a free Azure
account (or join the Microsoft 365 Developer Program) to get one
([Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/1663127/question-regarding-notification-about-being-requir),
[registration quickstart](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app)).
That step is the most tedious part of the setup.

Whose app registration should the sign-in use? And once someone has several Microsoft accounts, do
they need one registration per account?

## Decision Drivers

* No person or project should own an app that every user's source accounts have granted mail
  access to.
* One user's misuse must not lock out every other user.
* Setup should stay possible for someone with only a personal Microsoft account.
* Several accounts must not compete for one throttling budget.
* The README's setup steps must be complete for the path it recommends.

## Considered Options

* Every user registers their own app, and one registration serves all of that user's accounts
* The maintainer registers one app and the code ships its client ID as the default
* Borrow a Microsoft first-party client ID
* Require one app registration per account

## Decision Outcome

Chosen option: "Every user registers their own app, and one registration serves all of that user's
accounts", because it is the only option where nobody else's app can be blocked, revoked or held
responsible for your source accounts, and Microsoft counts throttling per app and per source
account, so one registration per user costs nothing.

The project ships no client ID. `microsoft.clientId` holds the user's own registration and applies
to every account. `accounts[].clientId` overrides it for one account, for people who already have
separate registrations. It is never required, and it adds no throughput.

### Consequences

* Good, because a blocked or deleted registration affects only the person who owns it.
* Good, because the maintainer answers for no app that strangers' source accounts have granted
  access to.
* Good, because every account keeps its own budget of Outlook requests even when all accounts share
  one registration.
* Bad, because every user goes through the Entra admin center once, and a personal account first
  needs a directory. The README section "Register an app" walks through it, including the Azure
  sign-up checks.
* Neutral, because `accounts[].clientId` stays as an option that most people never set.

### Confirmation

* `src/main/java/ch/fmartin/outlooktoimap/config/Config.java` has no default value for
  `Microsoft.clientId`.
* `ConfigLoaderTest.reportsEveryProblemAtOnce` checks that an account without a client ID is
  rejected with "has no clientId: set microsoft.clientId or the account's clientId".
* `ConfigLoaderTest.readsEverySetting` checks that `accounts[].clientId` overrides the top-level one.
* The README section "Register an app" still describes the registration steps and says one
  registration covers every account.

## Pros and Cons of the Options

### Every user registers their own app, and one registration serves all of that user's accounts

There is no default client ID. Each user creates a registration of the type "Personal Microsoft
accounts only", turns on public client flows and copies its client ID into `microsoft.clientId`.
All of that user's accounts sign in through it, each with its own consent and its own token file.

* Good, because each user controls their own registration and can delete it at any time.
* Good, because one user's misuse can only get their own registration blocked.
* Good, because the Outlook limits count each source account separately, see "More Information".
* Bad, because the setup has an extra step that needs an Entra directory.

### The maintainer registers one app and the code ships its client ID as the default

The maintainer creates one registration and the code uses its client ID unless the configuration
names another. Every user sees that app's name on the consent screen.

* Good, because nobody has to register anything.
* Bad, because every stranger's sign-in goes through the maintainer's registration. If one of them
  misuses it, Microsoft can block the app for everyone at once, the maintainer included.
* Bad, because the maintainer would answer for an app that many source accounts granted mail access
  to.
* Bad, because work and school users often can't consent to it. Since November 2020, users in a
  tenant with risk-based step-up consent can't consent to most newly registered multitenant apps
  without a verified publisher, and publisher verification needs a verified Microsoft AI Cloud
  Partner Program account and an app registered with a work or school account
  ([publisher verification overview](https://learn.microsoft.com/en-us/entra/identity-platform/publisher-verification-overview)).

### Borrow a Microsoft first-party client ID

Use the client ID of a Microsoft app that already accepts device code sign-in, such as the one
Graph PowerShell uses.

* Good, because nobody has to register anything.
* Bad, because the service would present another app's identity on the consent screen.
* Bad, because Microsoft can restrict that client ID at any time, without notice to this project.

### Require one app registration per account

Each Microsoft account gets its own registration and its own client ID.

* Good, because accounts are fully separated at the app level.
* Bad, because it multiplies the most tedious setup step by the number of accounts.
* Bad, because it buys nothing: each account already gives its own consent and gets its own tokens,
  and the throttling limits are per app and per source account, not per app.

## More Information

Microsoft's Outlook service limits apply "to each app ID and mailbox combination", and exceeding
the limit for one source account doesn't affect the app's access to another
([Microsoft Graph throttling limits, Outlook service limits](https://learn.microsoft.com/en-us/graph/throttling-limits#outlook-service-limits),
read on 2026-10-26). Per app and source account, the page lists 10,000 API requests in 10
minutes, four concurrent requests, and 150 MB of uploads in 5 minutes. The service sends one Graph request at a
time, from one thread, so the concurrency limit is never reached, and it uploads nothing to
Microsoft.

What happens when a source account does hit the request limit, and how the retries work, is in
[ADR 0007](0007-throttled-accounts-wait-for-the-next-poll.md). Work and school accounts, which need
a different registration type, are in [ADR 0009](0009-work-and-school-accounts-best-effort.md).

The owner chose this option on 2026-10-26 from three: every user registers their own app, the
maintainer ships a shared client ID as the default, or the service borrows a Microsoft first-party
client ID. Later the same day, the owner confirmed that one registration is enough for several
accounts. [junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66),
which asked for the service, does not say whose app registration to use. The decision shipped with
[outlook-to-imap pull request #5](https://github.com/martinfrancois/outlook-to-imap/pull/5).
