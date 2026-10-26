# One database layer for SQLite and PostgreSQL

Receipts, checkpoints and status live in SQLite by default, so a single container needs nothing
else. SQLite's locking is unreliable on NFS and SMB, where some people keep container volumes, so
PostgreSQL is supported as well.

Both engines run the same Flyway migrations and the same Jdbi statements. The SQL stays inside what
both accept: `ON CONFLICT ... DO UPDATE` for upserts, times as epoch milliseconds in `BIGINT`, no
engine-specific types. Every store test and the end-to-end backlog copy test run on both engines, the
PostgreSQL ones through Testcontainers. Adding a third engine means adding its JDBC driver and Flyway
module and the engine to that test parameter; no query should need to change.

Rejected: an ORM (more machinery than four small tables need) and an engine abstraction per
database (two code paths to keep in step).
