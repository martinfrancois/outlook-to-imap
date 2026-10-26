# Working on outlook-to-imap

Read `README.md` for what the service does, `CONTEXT.md` for the terms the code uses, and
`docs/adr` before changing anything an ADR covers.

## Build and test

- `./mvnw -B verify` runs every test and the 80% instruction coverage gate. CI runs exactly this.
- The PostgreSQL tests need Docker or Podman through Testcontainers. With rootless Podman, set
  `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` and `TESTCONTAINERS_RYUK_DISABLED=true`.
- `scripts/smoke-image.sh docker` (or `podman`) builds the image and checks it starts, answers
  `/health` and stops cleanly. Run it after changing the `Dockerfile`, `pom.xml` packaging or
  `Main`.

## Rules

- Never add code that sends mail, deletes on a destination, or asks Microsoft for `Mail.Send`.
  Source removal moves to Deleted Items and nothing further.
- A message with a receipt is never uploaded again. Keep that true for every new code path.
- SQL must run on both SQLite and PostgreSQL unchanged, see ADR 0003. Add schema changes as new
  Flyway migrations; never edit a released one.
- Tests use real protocol code: GreenMail for IMAP, WireMock for Graph and the Microsoft sign-in
  endpoints, Testcontainers for PostgreSQL. Don't replace them with mocks of the client libraries.
- Prefer an established library over hand-written protocol, retry or parsing code.
- Commit messages follow Conventional Commits; commitlint checks them and release-please builds the
  changelog from them.

## Architecture decision records

Durable decisions live in `docs/adr/` as [MADR 4](https://adr.github.io/madr/) records, one file
per decision, named `NNNN-short-title-in-kebab-case.md` and numbered after the highest record on the
target branch. Copy `docs/adr/adr-template.md` and fill every section with concrete project content;
no template placeholder survives. `AdrConformanceTest` checks the shape of every record as part of
`./mvnw -B verify`. Why the records use this format is in
`docs/adr/0006-record-decisions-as-madr-4.md`.

Write a record when:

- a choice can't be recovered from the code, and the obvious cleanup of a line would undo it;
- the owner and an agent weigh several options and pick one. The record names the alternatives and
  why they lost. A decision must not live only in chat, a pull request or a commit message;
- an approach, library or setting was tried or seriously considered and rejected for a reason that
  isn't obvious, so the next maintainer doesn't try it again;
- a measured or verified fact decided the choice, such as a library's default timeout read from its
  bytecode or Microsoft's throttling limits. Keep the numbers with the versions and conditions they
  were taken under;
- the code deviates from the issue that asked for it. State the deviation and why;
- a session settled several durable decisions. Review the conversation and the commit history before
  finishing and capture them.

If the owner has to ask for a record after a decision was already made, add the record and sharpen
the trigger above that should have fired.

How to write one:

- Keep the MADR structure: YAML front matter with `status`, `date`, `decision-makers`, `consulted`
  and `informed`; then `Context and Problem Statement`, `Decision Drivers`, `Considered Options`,
  `Decision Outcome` with `Consequences` and `Confirmation`, `Pros and Cons of the Options`, and
  `More Information` when there is more to say.
- `decision-makers` is `[martinfrancois]` for a choice the owner made. For a choice the coding agent
  made, write `implementing coding agent`, plus how the owner approved it when the owner did, such
  as `approved by martinfrancois in the design plan`. `consulted` names only people or parties who
  gave input, and may be `[]`.
- Open every option under `Pros and Cons of the Options` with one or two sentences saying what the
  option concretely is, before any Good or Bad bullet.
- Status is `accepted`, or `superseded by [ADR NNNN](NNNN-short-title.md)` once a later record
  replaces it; the test rejects anything else. Never rewrite an accepted decision to match a later
  one. Supersede it and add a short note near the top. Correcting a fact, a link or the wording
  without changing the decision is fine.
- Cite issues and pull requests as full Markdown links, such as
  `[outlook-to-imap issue #2](https://github.com/martinfrancois/outlook-to-imap/issues/2)`, also
  inside YAML lists. No bare `#2` and no bare URL.
- Say in `Confirmation` how to check that the decision still holds: a test name, a command, a file
  to look at.
- Point at the record from the code it protects: a one-line comment that says what breaks if the
  line goes, plus the record's path.
- Write plain, factual, short sentences with no em dashes, for any person or agent on any machine.
