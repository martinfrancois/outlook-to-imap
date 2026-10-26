# outlook-to-imap

Copies mail from Outlook.com, Hotmail and Live mailboxes into folders on any IMAP server, such as
Fastmail. It reads through Microsoft Graph and uploads each message unchanged with IMAP `APPEND`. It
runs unattended in Docker or Podman, or straight from the jar.

Not affiliated with Microsoft. Outlook, Hotmail and Microsoft 365 are trademarks of Microsoft.

The case it was written for: Outlook's Junk folder catches real mail, and empties itself after a
few days. Forwarding that folder sends spam out through your Microsoft account, and fetch tools such
as Fastmail's only read the Inbox. outlook-to-imap copies Junk into a review folder at Fastmail
instead, and nothing is ever sent.

## What it does

- **Copies, one way.** Each folder mapping copies one Microsoft folder into one IMAP folder. Apart
  from creating a missing destination folder, nothing on the IMAP side is ever changed or deleted.
- **Copies each message once.** A message that was copied is never copied again: not on the next
  poll, not after you delete the copy at the destination, not after you move the original to another
  synced folder in Outlook. The [database](#data-and-backups) remembers each message by its
  Microsoft id.
- **Keeps the message as it was.** Sender, recipients, subject, every header, attachments and dates
  stay as Microsoft stored them. The copy arrives unread, dated with the time it reached Outlook.
  Nothing is prefixed and no sender is rewritten. Attachments are stored as data; nothing is opened,
  no remote content is loaded and no link is followed.
- **Never sends mail.** It has no SMTP code and never asks Microsoft for the permission to send.
- **Leaves the source alone by default.** With `removeSourceAfterSync: true` on a folder, each
  message moves to Outlook's Deleted Items after its copy is verified at the destination. Deleted
  Items is recoverable; nothing is purged. See [Source removal](#source-removal).
- **Survives crashes.** An upload whose reply never arrived is checked against the destination
  before anything is uploaded again. See [Reliability](#reliability).

## Requirements

- A Microsoft app registration of your own, see the next section. One registration covers every
  account you add.
- An IMAP server reachable over TLS (port 993) or STARTTLS (port 143), with a password or app
  password, see [Destinations](#destinations).
- Docker or Podman, or Java 21 or newer to run the jar.

## Register an app

Microsoft only lets an application read mail after the mailbox owner signs in to that application,
so you need an app registration. It's free. Since June 2024, every new registration has to live
in a Microsoft Entra directory, and a personal Microsoft account doesn't have one by default.

1. Open the [Microsoft Entra admin center](https://entra.microsoft.com) and sign in. If it says
   your account has no directory, create a free
   [Azure account](https://azure.microsoft.com/free/) with the same Microsoft account. That creates
   one, and the steps below never create anything that costs money.
   Azure signup can require phone and payment-card verification, and Microsoft may require you to
   set up multifactor authentication. Complete those steps yourself in Microsoft's browser pages.
   After signup, select the new directory in the portal. If the portal still shows the old
   no-directory error, refresh it before trying registration again. You don't need to deploy an
   Azure resource for this service.
2. Go to **Entra ID > App registrations > New registration**.
3. Name it `outlook-to-imap`, or anything else. Your accounts will see this name when they sign in.
4. Under **Supported account types**, choose **Personal Microsoft accounts only**. For work or
   school accounts, see [Work and school accounts](#work-and-school-accounts).
5. Leave **Redirect URI** empty and select **Register**.
6. Copy the **Application (client) ID** from the overview page into `microsoft.clientId`.
7. Open **Authentication**, set **Allow public client flows** to **Yes**, and save. The sign-in
   code flow below needs it. In the newer Authentication page, this switch is under **Settings**.

The app needs no secret and no certificate. You don't have to add permissions under
**API permissions** either: each account grants `Mail.Read` (or `Mail.ReadWrite`, for source
removal) when it signs in.

Microsoft throttles Graph per app and per mailbox, at 10,000 requests in 10 minutes. Every account
has its own budget, so one registration for all your accounts costs nothing.
[ADR 0001](docs/adr/0001-each-user-registers-their-own-app.md) and
[ADR 0007](docs/adr/0007-throttled-accounts-wait-for-the-next-poll.md) have the details.

## Configuration

One YAML file, `config.yaml` by default, or the path in `--config` or `OUTLOOK_TO_IMAP_CONFIG`.
[`examples/config.yaml`](examples/config.yaml) is a complete one to start from. Relative paths in
it are resolved against the directory the file is in. Unknown keys are an error, so a typo can't
switch a setting off without you noticing.

```yaml
microsoft:
  clientId: 00000000-0000-0000-0000-000000000000

pollInterval: PT1H

destinations:
  fastmail:
    host: imap.fastmail.com
    username: you@fastmail.com
    passwordFile: secrets/fastmail-app-password

accounts:
  - name: hotmail
    email: you@hotmail.com
    folders:
      - source: junkemail
        destination: fastmail
        folder: Outlook Junk
        removeSourceAfterSync: false
```

| Key | Default | Meaning |
| --- | --- | --- |
| `microsoft.clientId` | none | Your app registration's client ID. Required here or on every account. |
| `microsoft.tenant` | `consumers` | Who may sign in: `consumers` for personal accounts. See [Work and school accounts](#work-and-school-accounts). |
| `pollInterval` | `PT1H` | Time between the end of one poll and the start of the next, as an ISO-8601 duration from `PT1M` to `P30D`. |
| `stateDirectory` | `data` next to the config file; `/data` in the image | Holds the login files and, by default, the SQLite database. |
| `database.url` | `jdbc:sqlite:<stateDirectory>/outlook-to-imap.db` | `jdbc:sqlite:...` or `jdbc:postgresql://host/db`. See [Data and backups](#data-and-backups). |
| `database.username` | none | PostgreSQL user. |
| `database.passwordFile` | none | File holding the PostgreSQL password. |
| `monitoring.healthPort` | `8080` | Port of `GET /health` while `run` is active. `0` turns it off. |
| `monitoring.heartbeatUrl` | none | A healthchecks.io-style ping URL, see [Monitoring](#monitoring). |
| `destinations.<name>.host` | none | IMAP server. |
| `destinations.<name>.port` | `993` for `tls`, `143` for `starttls` | |
| `destinations.<name>.security` | `tls` | `tls` or `starttls`. There is no plain-text option. |
| `destinations.<name>.username` | none | IMAP login. |
| `destinations.<name>.passwordFile` | none | File holding the password or app password. Surrounding whitespace is ignored. |
| `accounts[].name` | none | Your name for the account, used in commands and logs. Letters, digits, `.`, `_`, `-`. Receipts are kept per name, so renaming an account copies what is still in its folders again. |
| `accounts[].email` | none | The Microsoft account's address. Signing in as anyone else is refused. |
| `accounts[].clientId` | `microsoft.clientId` | A different app registration for this account. |
| `accounts[].tenant` | `microsoft.tenant` | |
| `accounts[].folders[].source` | none | A Graph well-known folder such as `junkemail`, `inbox`, `archive` or `sentitems`, or a path of folder names such as `Projects/2026` or `Inbox/Newsletters`. Names are matched ignoring case. Subfolders are not included; list each folder you want. |
| `accounts[].folders[].destination` | none | A name under `destinations`. |
| `accounts[].folders[].folder` | none | The IMAP folder, created if it doesn't exist. Use `/` between levels, such as `Outlook/Junk`. |
| `accounts[].folders[].removeSourceAfterSync` | `false` | See [Source removal](#source-removal). |

Passwords and tokens never go in this file. It still contains your email addresses and folder
names, so remove those before sharing it. Keep the password files and the state directory out of
Git and shared or cloud-synced folders.

## Run it

All three ways run the same jar and read the same configuration. Pick the one you already use.

### Docker

```bash
umask 077
mkdir -p outlook-to-imap/secrets && cd outlook-to-imap
# write config.yaml, see Configuration
# Save the app password as plain text in secrets/fastmail-app-password using your editor.
# Do not put the password in a shell command, where shell history could save it.
chmod 700 secrets
chmod 600 config.yaml secrets/fastmail-app-password
# The image runs as uid 10001 and must read the config and traverse the secrets directory.
sudo chown -R 10001 config.yaml secrets

docker volume create outlook-to-imap-data

# Sign each account in once, see Sign in
docker run --rm -it \
  -v "$PWD/config.yaml:/config/config.yaml:ro" -v "$PWD/secrets:/config/secrets:ro" \
  -v outlook-to-imap-data:/data \
  ghcr.io/martinfrancois/outlook-to-imap:1 authorize hotmail

# Run as a service
docker run -d --name outlook-to-imap --restart unless-stopped --stop-timeout 30 \
  -v "$PWD/config.yaml:/config/config.yaml:ro" -v "$PWD/secrets:/config/secrets:ro" \
  -v outlook-to-imap-data:/data \
  ghcr.io/martinfrancois/outlook-to-imap:1
```

Or with Compose, using [`examples/compose.yaml`](examples/compose.yaml):

```bash
docker compose run --rm outlook-to-imap authorize hotmail
docker compose up -d
```

The image runs as user `outlook-to-imap` (uid 10001), not root. The ownership setup above gives it
the configuration, the secrets directory and its files while keeping them private. Do this before
the first `authorize` command. You may need `sudo` to edit those host files afterward. For a jar
running as your own user, keep their ownership unchanged.

### Podman

The same commands work with `podman` in place of `docker`. On SELinux hosts such as Fedora, add
`:Z` to bind mounts, for example `-v "$PWD/config.yaml:/config/config.yaml:ro,Z"`. Rootless
Podman maps uid 10001 into your user namespace. In the setup above, replace the `sudo chown` command
with `podman unshare chown -R 10001 config.yaml secrets` before the first `authorize` command.

```bash
podman run --rm -it \
  -v "$PWD/config.yaml:/config/config.yaml:ro,Z" -v "$PWD/secrets:/config/secrets:ro,Z" \
  -v outlook-to-imap-data:/data \
  ghcr.io/martinfrancois/outlook-to-imap:1 authorize hotmail

podman run -d --name outlook-to-imap --restart unless-stopped --stop-timeout 30 \
  -v "$PWD/config.yaml:/config/config.yaml:ro,Z" -v "$PWD/secrets:/config/secrets:ro,Z" \
  -v outlook-to-imap-data:/data \
  ghcr.io/martinfrancois/outlook-to-imap:1
```

`--restart unless-stopped` restarts the container when it exits. After a reboot, Docker starts it
again by itself; Podman only does so when its own `podman-restart` service is enabled on the host.
`podman compose` reads the same `compose.yaml`.

### Java

Download `outlook-to-imap-<version>.jar` from the
[releases](https://github.com/martinfrancois/outlook-to-imap/releases), or build it with
`./mvnw package`, which writes `target/outlook-to-imap.jar`.

```bash
java -jar outlook-to-imap.jar --config config.yaml authorize hotmail
java -jar outlook-to-imap.jar --config config.yaml run
```

It needs Java 21 or newer. CI tests every Java LTS release from 21 up. The state directory defaults
to `data` next to the configuration file.

### Commands

| Command | What it does | Exit code |
| --- | --- | --- |
| `authorize <account>` | Signs one account in, see [Sign in](#sign-in). | 0 when saved |
| `run` | Polls right away, then once per `pollInterval`, until stopped. The image runs this by default. | 143 after SIGTERM (`docker stop`), 130 after Ctrl-C |
| `once` | Polls every account once and exits. For a manual check, a test, or an external scheduler such as a Kubernetes CronJob. | 0 all accounts fine, 1 an account failed, 3 another process was polling |
| `once --dry-run` | Lists what a poll would copy and remove, and does neither. It records no receipts and moves no checkpoint, but it signs in, which saves the refreshed login, and it creates the database on first use. | as `once` |
| `status` | Prints each account's last successful poll, last attempt, counts and last error. | 0 |

An invalid configuration exits with 2 and lists every problem at once. Any command stopped by a
signal exits with 128 plus the signal's number, as other programs do: 143 for SIGTERM, 130 for
Ctrl-C.

A poll never overlaps another. `run` waits for the poll to end before counting the interval, and a
lease in the database stops a `once` from polling while another process with the same database is
mid-poll. When stopped (SIGTERM, `docker stop`), it finishes the message it's on and exits. A poll cut
short like that sends no heartbeat and doesn't count as a successful poll; the next one carries on
where it stopped.

## Sign in

```bash
docker compose run --rm outlook-to-imap authorize hotmail
```

It prints a line like:

```
To sign in, use a web browser to open https://microsoft.com/devicelogin and enter the code ABCD-EFGH to authenticate.
```

Open that page on any device, such as your phone, enter the code, and sign in with the account's
Microsoft address. The command saves the login and exits. Do it once per account. Signing in with a
different address than the account's `email` is refused and its login is removed again. If that
removal fails, the command says which file to delete by hand.

Use the exact URL printed by the command. For personal accounts Microsoft can return
`https://www.microsoft.com/link` with an eight-character code instead of the example above.
Entering that code at `microsoft.com/devicelogin` can fail. Keep the command running while you sign
in. If the code expires, run `authorize` again and use its new code.

The account grants `Mail.Read` and `offline_access`, which lets the service refresh its login
unattended. Accounts with source removal on any folder grant `Mail.ReadWrite` instead. Nothing
ever asks for `Mail.Send`.

### Staying signed in

Microsoft issues a new refresh token every time one is used, valid for 90 days from then. Every
poll uses the current one and saves its replacement, so with polls at most 30 days apart the login
never runs out on its own. The service writes the login file again after every refresh, with the
new file swapped in by an atomic rename.

These end a login early, and only `authorize` fixes them:

- You change the Microsoft account's password.
- You use "Sign out everywhere" on the account, or remove the app at
  [account.live.com/consent/Manage](https://account.live.com/consent/Manage).
- The service doesn't run for more than 90 days.
- You switch source removal on for an account that signed in without it, because the login lacks
  `Mail.ReadWrite`.

Then that account reports `needs a new login ... run outlook-to-imap authorize <account>` in the
log, in `status`, in `/health` and as a failed heartbeat. The other accounts keep running.

## Source removal

`removeSourceAfterSync` is set per folder and is `false` unless you set it.

- **`false`:** the source message is never touched. Outlook keeps it, and in Junk deletes it on its
  own schedule. Your copy stays at the destination either way.
- **`true`:** once the destination shows the copy and its receipt is saved, the message moves to
  Deleted Items, where you can restore it. If the move fails, the next poll tries again, without
  copying the message a second time. An upload whose outcome is unknown never leads to a removal.

Switching it on for a folder that already ran with it off also removes the messages copied
earlier: the next poll reads the whole folder again and moves every message it has a receipt for.
That includes messages whose copy you have since deleted at the destination. Deleting spam in
Fastmail and then switching removal on cleans the source too. Run `once --dry-run` first to see the
list.

Switching it on changes the permission the account needs, so run `authorize` again for that account
first. Without that, the account reports that it needs a new login, and the others carry on.

Moving the source to Deleted Items is as far as it goes. It never empties Deleted Items, marks
anything as "not junk" or moves anything to the Inbox.

## Destinations

Every destination is a plain IMAP server with a username and password over TLS or STARTTLS.
Messages arrive with IMAP `APPEND`, the command mail clients use to save drafts and import mail.

| Provider | Settings | Login |
| --- | --- | --- |
| Fastmail | `imap.fastmail.com`, `tls` | An [app password](https://www.fastmail.help/hc/en-us/articles/360058752854) with IMAP access. |
| Gmail | `imap.gmail.com`, `tls` | An [app password](https://support.google.com/accounts/answer/185833), which needs 2-Step Verification. |
| iCloud Mail | `imap.mail.me.com`, `tls` | An [app-specific password](https://support.apple.com/en-us/102654). |
| Dovecot or another server you run | your host, `tls` or `starttls` | The mailbox password. |

Servers that only accept OAuth logins don't work as destinations. That includes Outlook.com itself.

### Hotmail to Fastmail example

Use a Fastmail plan that supports IMAP. Fastmail's Basic plan does not support app passwords for
third-party mail clients. The [Fastmail app-password guide](https://www.fastmail.help/hc/en-us/articles/360058752854)
describes the current account requirements.

1. In Fastmail, open **Settings > Privacy & Security > Manage app passwords and access**. This
   opens **Connected apps**. Create a new app password, completing password verification if asked.
2. Give it a recognisable name, such as `outlook-to-imap`. Choose IMAP access only and leave
   **Read-only** unchecked, because copying mail needs IMAP write access. SMTP, contacts and
   calendar access are unnecessary.
3. Save the generated password in the file named by `passwordFile`, then close the password
   display. Use this app password, not your normal Fastmail password. Protect the file as shown
   under [Run it](#run-it), including container ownership if you run Docker or Podman.
4. Set `host: imap.fastmail.com`, `port: 993` and `security: tls`. For `username`, use your full
   primary Fastmail login address, not an alias. The account menu shows your login address.
   See [Fastmail's server settings](https://www.fastmail.help/hc/en-us/articles/1500000278342).
5. Use the configuration example above with your Hotmail address. Keep `source: junkemail` even
   if Outlook displays a translated Junk folder name. Choose a new ordinary destination folder,
   such as `Outlook test/Hotmail Junk`, and keep `removeSourceAfterSync: false`. The service creates
   the destination folder when it first uploads a message.
6. Run `authorize hotmail` and complete Microsoft's sign-in and mail-access consent. Then run
   `once --dry-run` to inspect what would be copied. A dry run checks the source, but does not
   prove that Fastmail accepts uploads.
7. Run `once`, check its exit code and `status`, and inspect the new folder in Fastmail. Copies
   should arrive unread, with the original received dates and attachments. The originals remain
   in Hotmail's Junk folder. Run `once` again with the same configuration and state directory.
   It should import zero messages unless new mail arrived between polls.

For example, when running the jar, the checks are:

```bash
java -jar outlook-to-imap.jar --config config.yaml once --dry-run
java -jar outlook-to-imap.jar --config config.yaml once
java -jar outlook-to-imap.jar --config config.yaml status
java -jar outlook-to-imap.jar --config config.yaml once
```

To test source removal, first copy a few messages into a new disposable folder in Outlook.
Add a folder mapping for that folder to another new Fastmail folder, and enable removal only on
that mapping. Leave the real Junk mapping set to `false`. Run `authorize` again for the added
`Mail.ReadWrite` permission, then dry-run and poll. Verify that only the disposable source copies
moved to Deleted Items and that another poll does not duplicate their destination copies.

For a second Hotmail or Live account, add another `accounts` entry with a distinct, permanent
`name` and a separate destination folder, then authorize that account. Both entries can use the
same app registration and Fastmail destination. You don't need a second browser session; select
the matching Microsoft account during each sign-in. If the browser keeps choosing the first
account, open an Incognito window or a separate browser profile, sign into Outlook with the
second account, and open that account's device-login URL in the same window. Signing into
Outlook alone does not authorize this service: complete the device-code flow and wait for
`Saved the login of account <name>` before polling.

Imported messages skip the destination's incoming-mail pipeline. Spam filters, sieve rules and
forwarding run when a message is delivered by SMTP, and an `APPEND` is not a delivery, so none of
them see these messages. Copies land exactly in the folder you configured and stay there until you
act on them. Copy into an ordinary review folder rather than the destination's spam folder, so
nothing there gets trained or purged by the provider's spam handling by accident.

## Monitoring

- **`GET /health`** on `monitoring.healthPort` (default 8080) answers 200 while polls make progress
  and 503 when the poll loop has made none for two intervals plus 15 minutes. The body is JSON with
  each account's last attempt, last success, counts and last error. The image's `HEALTHCHECK` calls
  it on port 8080. If you change `healthPort` or set it to `0` in the container, also replace the
  check (`--health-cmd`, or `healthcheck:` in Compose) or turn it off (`--no-healthcheck`), or the
  container shows as unhealthy. Account failures don't make it 503, because restarting the
  container won't fix a login.
- **Heartbeat.** Set `monitoring.heartbeatUrl` to a [healthchecks.io](https://healthchecks.io) check's
  ping URL. After every poll that runs to the end, the service POSTs the summary to the URL when
  every account worked, and to the URL plus `/fail` when one didn't. Set the check's period to your `pollInterval` and a
  grace time of an hour or so. healthchecks.io then alerts on a failed poll and on pings that stop
  coming, which covers a stopped container, a dead host and a login that needs renewing. Anything
  that accepts the same two URLs works, including a self-hosted healthchecks instance.
- **`status`** prints the same per-account information on the command line.
- **Logs** go to standard output: one line per poll per account with imported, removed, skipped and
  failed counts, and one line per failure. Tokens, passwords and message content never appear in
  them. Set `LOG_LEVEL=DEBUG` for more.

## Reliability

Every message has a receipt in the database before its upload starts, holding the SHA3-256 hash of
exactly the bytes being sent.

- **An upload that fails or whose reply never arrives** stays pending. The next attempt first
  searches the destination it went to by Message-ID (or by Date, for a message without one) and
  compares the full content of each candidate with the recorded hash. A match is recorded as
  imported; otherwise the message is uploaded. Spam often reuses Message-IDs, so the id alone never
  counts. If that destination has since left the configuration, the message is copied to the
  current one.
- **A copy counts as imported** only once the server lists it in the folder, not when it answers OK.
- **One message failing** doesn't stop the others. The folder's position stays where it was, so the
  next poll sees the failed message again; the messages that worked are skipped by their receipts.
- **One folder or account failing** doesn't stop the others either. That includes an account
  that needs a new login, and one that Microsoft keeps throttling.
- **Throttling.** Each request is retried up to 5 times. Every retry waits as long as Microsoft's
  `Retry-After` asks, or backs off exponentially without one, but never more than 180 seconds,
  the Graph SDK's limit. A mailbox still throttled after that is skipped until the next poll.
- **First run and downtime.** The first poll of a folder reads all of it, which copies the backlog.
  Later polls ask Graph only for what changed since. A position Graph no longer accepts (after long
  downtime) leads to a full reread, and the receipts skip everything already copied.

One limit: if the destination server changes messages as it stores them, the content check can't
match them. A crash at exactly the moment between storing and answering would then leave a
duplicate. In the live check before 1.0.0, Fastmail stored the copies unchanged.

Local mail-scanning software can also change the bytes returned by IMAP. For example, an antivirus
can add `X-Antivirus` headers to fetched mail even when Fastmail's **Show raw message** view shows
the original unchanged. This also prevents the pending-upload content check from matching. Check
the scanner's settings and repeat a small copy-and-readback test before relying on crash recovery.
Do not ignore header differences in the fingerprint check.

For a controlled diagnostic on Avast Security for Mac, the setting is **Preferences > Email
Guard > Scan secure connections**. Temporarily turning it off stops inspection of encrypted
mail traffic on that Mac. Record its original state, test with a fresh IMAP connection, and
restore it immediately afterward. Keep the other shields and TLS verification enabled. An
email-domain exception did not prevent readback changes in the live check. For unattended use,
choose an environment where IMAP readback preserves the uploaded bytes; a diagnostic with
scanning off does not prove recovery will work after scanning is restored.

If Java reports `PKIX path building failed`, check the certificate issuer. A local TLS-inspecting
scanner may use a root certificate that macOS trusts but Java does not. Use only a certificate
you have verified and already trust, in a dedicated Java trust store that also retains the JDK's
public roots. Replacing those roots with only a local certificate can fix IMAP while breaking
Microsoft sign-in. Keep certificate and hostname verification enabled.

## Data and backups

The state directory holds:

- `tokens/<account>.json`: each account's login, readable only by its owner (mode 600, directory
  700). Anyone with this file can read that mailbox until the login is revoked.
- `outlook-to-imap.db`: the SQLite database, unless `database.url` points elsewhere. It holds one
  receipt per copied message (Microsoft id, destination, hash, size, times; no content), each
  folder's position, and the last poll's status.

The database is the only record of what was copied. Lose it, and the next poll copies everything
still in the source folders again. Back it up with the token files.

SQLite needs a local disk. Its file locking is unreliable on network file systems such as NFS or
SMB, so if the state directory lives on one, point `database.url` at PostgreSQL:

```yaml
database:
  url: jdbc:postgresql://db.internal:5432/outlook_to_imap
  username: outlook_to_imap
  passwordFile: secrets/postgres-password
```

The schema is created on first start. SQLite and PostgreSQL are the two supported engines; CI tests
both. The token files are small and rewritten whole by rename, which is safe on NFS.

## Work and school accounts

Work and school (Microsoft Entra) accounts are best effort: untested and not officially supported.
Pull requests that make them work better are welcome.

To try: register the app with **Any Entra ID Tenant + Personal Microsoft accounts** as the account
type, and set `tenant` to `organizations`, `common` or your tenant ID. Problems you are likely to
meet:

- Many organisations block users from consenting to apps, so an administrator has to approve the
  app first.
- Conditional Access policies often block the device code sign-in this tool uses. Microsoft
  recommends blocking it.
- Your organisation's policies may forbid copying mail out of its tenant at all. Check first.

## Moving from junkEmailMover

[junkEmailMover](https://github.com/martinfrancois/junkEmailMover) moved Junk back into the Inbox
over IMAP with a stored password, and could forward each message through SMTP with `[SPAM]` added
to the subject. outlook-to-imap replaces it. Nothing carries over:

1. Stop the old job, such as the cron entry or scheduled task that ran `junkEmailMover.jar`.
2. Delete the passwords it stored. Running the old jar with no arguments clears them from the Java
   preferences store.
3. If you created app passwords for junkEmailMover in your Microsoft account, revoke them.
4. Register an app, write `config.yaml` and sign each account in, as described above.
5. Start the service. The first poll copies what is in the Junk folders now. Mail that
   junkEmailMover already moved to the Inbox stays there. Add an `inbox` folder mapping if you want
   it copied too.

One difference remains: junkEmailMover's forwards left your Microsoft account as outgoing
mail, and outlook-to-imap sends nothing.

## Development

```bash
./mvnw -B verify
```

That runs every test, including the PostgreSQL tests through
[Testcontainers](https://java.testcontainers.org/), and fails the build below 80% instruction
coverage. Testcontainers needs Docker or Podman. With rootless Podman:

```bash
systemctl --user enable --now podman.socket
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
./mvnw -B verify
```

The tests run against a real IMAP server (GreenMail) and against Microsoft Graph and the Microsoft
sign-in endpoints as played by WireMock, so a dependency upgrade that changes protocol behaviour
fails the build.

`scripts/smoke-image.sh docker` (or `podman`) builds the image and checks that it starts, answers
`/health`, reports a missing login and stops cleanly. CI runs it too.

CI tests every Java LTS from 21 upward, as listed by [endoflife.date](https://endoflife.date/eclipse-temurin)
at run time, so a new LTS is tested from its release day. The required check on `main` is the `build`
job, which reports the result of all the others. Renovate proposes newer LTS versions for the
release build's JDK and for the build and runtime images; raising the minimum of 21 waits for
approval on the Dependency Dashboard.

Releases come from [release-please](https://github.com/googleapis/release-please): merging its pull
request tags the version, attaches the jar to a GitHub Release and pushes the image to
`ghcr.io/martinfrancois/outlook-to-imap` for amd64 and arm64.

Design decisions are in [`docs/adr`](docs/adr) and the terms used in the code are in
[`CONTEXT.md`](CONTEXT.md).

## License

[MIT](LICENSE)
