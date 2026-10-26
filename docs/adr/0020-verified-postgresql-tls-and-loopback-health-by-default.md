---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository, Users upgrading from 1.0.0]
---

# Verified PostgreSQL TLS and a loopback /health by default, released as 2.0.0

## Context and Problem Statement

The security audit of 1.0.0 found two defaults that exposed data to the network
([outlook-to-imap pull request #31](https://github.com/martinfrancois/outlook-to-imap/pull/31)).

Store passed the PostgreSQL URL to pgjdbc as written. Without an `sslmode`, pgjdbc 42.7.14 uses
`prefer`: it tries TLS, falls back to plain text when the server or anyone in between declines, and
never checks the certificate. Anyone on the network path could read the database password and every
receipt, or answer with changed rows such as another delta link. The README recommends PostgreSQL
exactly when the database is on another machine.

`HealthServer` listened on the wildcard address. A jar run on a host answered `/health` on every
interface, without authentication, and the reply names each account and its last error, which can
hold file paths, IMAP server replies and Graph error codes.

Safe defaults for both break setups that work with 1.0.0: a PostgreSQL server without TLS or with a
certificate the driver cannot verify, and anything that checks `/health` from outside the host or
the container: a published port, another container on the same network, a Kubernetes `httpGet`
probe, which connects to the pod's IP, or a monitor on another machine. Which defaults does the
service use, and how is the change released?

## Decision Drivers

* Data must not cross a network unencrypted or reach anyone who did not ask for it, unless the owner
  of the setup says so.
* A PostgreSQL setup that the new default breaks must fail at start with a message that says what
  to change, not run half-working. The `/health` change can't do that: the service runs and only a
  check from outside gets no answer, so the upgrade notes name each such check.
* An upgrade that can break a working setup must be visible as such before people install it.
* Every way to keep the 1.0.0 behavior must be one setting, written down in the README.

## Considered Options

* `verify-full` and `127.0.0.1` by default, released as 2.0.0
* The same defaults, released as 1.0.1
* Softer defaults: `require` for PostgreSQL, and `127.0.0.1` only inside the image
* Keep the 1.0.0 defaults and document the risk

## Decision Outcome

Chosen option: "`verify-full` and `127.0.0.1` by default, released as 2.0.0", because only
`verify-full` protects against someone in the middle, only a loopback default protects a jar run on
a host, and a major version tells every user before the upgrade that a working setup may stop. The
owner chose the major release on 2026-10-26 over shipping the defaults as fixes in 1.0.1 or
softening them.

PostgreSQL:

* `Store.open` sets the data source property `sslmode=verify-full` when neither the URL nor the
  pg_service.conf entry it names sets `sslmode` or `ssl`. pgjdbc reads `sslmode` before `ssl`, so
  setting the property unconditionally would turn a URL's `ssl=false` into `verify-full`. pgjdbc's
  own `Driver.parseURL` reads the URL, so the check follows the driver's parsing.
* The certificate is checked the way pgjdbc does by default: against `~/.postgresql/root.crt`
  (`/data/.postgresql/root.crt` in the image) or the file named by `sslrootcert`, not against
  Java's trust store. A self-hosted PostgreSQL usually has a certificate from a private CA, and this
  way that CA trusts only the database connection instead of every TLS connection of the JVM. A
  certificate from a public CA works by adding `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`
  to the URL.
* `sslmode=disable` or `ssl=false` in the URL connects without TLS as 1.0.0 did against a server
  without TLS; `sslmode=require` encrypts without checking the certificate.

`/health`:

* The new setting `monitoring.healthBindAddress` defaults to `127.0.0.1`
  (`Config.Monitoring.DEFAULT_HEALTH_BIND_ADDRESS`). `0.0.0.0` restores the 1.0.0 behavior. A
  setup needs it whenever something outside the host or the container checks `/health`: a
  published port, another container, a Kubernetes probe or a remote monitor.
* The image's `HEALTHCHECK` calls `127.0.0.1` inside the container, so it keeps working with the
  default. A bind address that does not accept connections to `127.0.0.1`, such as `::1`, fails it.
* A host name that does not resolve fails `run` with a configuration error that names the setting.

Both changes are `fix!:` commits with a `BREAKING CHANGE:` footer, so release-please proposes 2.0.0
and the changelog lists what to change.

### Consequences

* Good, because a PostgreSQL connection is encrypted and the server proven by default, and a
  wrong certificate or a server without TLS stops the service at start with pgjdbc's message.
* Good, because `/health` answers only the host, or only the container, unless the owner opens it.
* Good, because the major version and the changelog warn before the upgrade.
* Bad, because a 1.0.0 setup against PostgreSQL without a verifiable certificate, or one where
  `/health` is checked from outside the host or the container, stops working after the upgrade
  until one setting changes. A Kubernetes liveness probe that gets no answer restarts the container
  again and again.
* Bad, because the image tag `1` stays on 1.x. Users who follow
  `ghcr.io/martinfrancois/outlook-to-imap:1` get the fixes only by moving to `2`. The README and
  `examples/compose.yaml` follow by themselves: release-please rewrites each image tag marked
  `x-release-please-major` to the new major version in the release pull request.

### Confirmation

`StoreTest.postgresqlNeedsAVerifiedTlsConnectionUnlessTheUrlSaysOtherwise` connects without TLS
only through `sslmode=disable` or `ssl=false`, and fails with "does not support SSL" otherwise.
`ConfigLoaderTest.appliesDefaults` checks the `127.0.0.1` default, and
`MainTest.aHealthBindAddressThatDoesNotResolveIsAConfigurationError` the error for a name that does
not resolve. `scripts/smoke-image.sh` checks that the image's own health check passes with the
default. The 2.0.0 changelog lists both changes under breaking changes.

## Pros and Cons of the Options

### `verify-full` and `127.0.0.1` by default, released as 2.0.0

Both safe defaults, each with a one-setting way back, in a major release.

* Good, because the defaults protect users who never read the security notes.
* Good, because semantic versioning tells users, and tools such as Renovate, that the upgrade can
  break a setup.
* Bad, because users pinned to `1` or `1.0` do not get the change without acting.

### The same defaults, released as 1.0.1

Treat both as bug fixes in a patch release.

* Good, because everyone who follows `1` gets the protection at once.
* Bad, because a patch release that stops a working service at start breaks the promise of a patch
  version, and the failure arrives without warning, often through an automatic image update.

### Softer defaults: `require` for PostgreSQL, and `127.0.0.1` only inside the image

`sslmode=require` encrypts but checks no certificate. The image would set the bind address through
an environment variable, and the jar would keep listening on every interface.

* Good, because fewer 1.0.0 setups break: any TLS server works, and a jar keeps answering remotely.
* Bad, because `require` does not stop someone in the middle, who can present any certificate.
* Bad, because the jar on a host is the case that exposes `/health` to a network; the container
  is already isolated unless a port is published.

### Keep the 1.0.0 defaults and document the risk

Leave `prefer` and the wildcard address, and explain in the README how to tighten them.

* Good, because nothing breaks.
* Bad, because the users who don't read that section are the ones exposed.

## More Information

pgjdbc's `SslMode.of` in 42.7.14 returns `verify-full` when `sslmode` is missing and `ssl` is
`true` or empty, and `prefer` when both are missing. `Driver.parseURL` returns the URL's
parameters merged with the pg_service.conf entry the URL names. The review of the first version found
that the unconditional `sslmode` property overrode a URL's `ssl=false`; the check for both
parameters came from that review round.
