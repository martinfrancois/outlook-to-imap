---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository]
---

# Work and school accounts are best effort

## Context and Problem Statement

[junkEmailMover issue #66](https://github.com/martinfrancois/junkEmailMover/issues/66) asked for
personal Microsoft accounts: Outlook.com, Hotmail and Live. Supporting work and school (Microsoft
Entra) accounts in code is small. The sign-in authority changes from `consumers` to `organizations`,
`common` or a tenant ID, and the app registration type changes to "Any Entra ID Tenant + Personal
Microsoft accounts". The cost lies outside the code: organisations often block it, and nobody on the
project can test it.

Should the service accept work and school accounts, and what may the README promise about them?

## Decision Drivers

* The README must not promise something nobody has seen work.
* People with work accounts should be able to try it and send fixes.
* The default configuration must stay right for personal accounts.
* No live test is possible: the free directory created for the app registration has no work
  account with mail to read.

## Considered Options

* Accept work and school accounts, marked best effort: untested and not officially supported
* Personal accounts only

## Decision Outcome

Chosen option: "Accept work and school accounts, marked best effort: untested and not officially
supported", because the code cost is one setting, and marking it best effort keeps the README from
promising more than was tested. The owner added that pull requests improving work and school
support are welcome.

The default tenant is `consumers` (`Config.Microsoft.DEFAULT_TENANT`). `microsoft.tenant` and
`accounts[].tenant` accept any other value, which `MicrosoftLogin` puts into the authority URL. The
README section "Work and school accounts" says they are untested and not officially supported, and
lists the problems people are likely to meet.

### Consequences

* Good, because a work account can be tried with two settings and no code change.
* Good, because the README is honest about what was tested.
* Bad, because a user may spend time on a setup their organisation blocks. The README lists the
  known blockers so they can check first.
* Neutral, because a bug report about a work account has no tested baseline to compare against.

### Confirmation

`Config.Microsoft.DEFAULT_TENANT` is `consumers`. `ConfigLoaderTest.readsEverySetting` reads
`tenant: organizations` from the configuration. The README section "Work and school accounts" still
says "best effort: untested and not officially supported".

## Pros and Cons of the Options

### Accept work and school accounts, marked best effort: untested and not officially supported

The tenant is configurable globally and per account. The README explains the registration type and
tenant values, and lists the expected problems.

* Good, because the code already supports it with one configurable value.
* Good, because people who need it can try it and send fixes.
* Bad, because many organisations block user consent to apps, so an administrator has to approve
  the app first.
* Bad, because Conditional Access policies often block the device code sign-in this tool uses, and
  Microsoft recommends blocking it.
* Bad, because an organisation's policy may forbid copying mail out of its tenant at all.

### Personal accounts only

Accept only `consumers` and document personal accounts alone, as the issue asked.

* Good, because everything the README claims would be tested.
* Bad, because people with work accounts could not even try it without changing code.

## More Information

The agent recommended personal accounts only for 1.0.0, on the grounds that a README claim like
"works with work accounts" without anyone having seen it work is a promise the project should not
make. The owner chose the best-effort option on 2026-10-26 and asked for it to be marked as such.

The sign-in flow itself is in
[ADR 0008](0008-device-code-sign-in-with-a-private-token-file-per-account.md). A shared app
registration would face extra consent hurdles in work tenants, see
[ADR 0001](0001-each-user-registers-their-own-app.md).
