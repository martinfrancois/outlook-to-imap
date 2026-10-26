# Working on outlook-to-imap

Read `README.md` for what the service does, `CONTEXT.md` for the terms the code uses, and
`docs/adr` before changing anything an ADR covers.

## Build and test

- `mvn -B verify` runs every test and the 80% instruction coverage gate. CI runs exactly this.
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
