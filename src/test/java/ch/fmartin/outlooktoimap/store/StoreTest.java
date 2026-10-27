package ch.fmartin.outlooktoimap.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.outlooktoimap.Databases;
import ch.fmartin.outlooktoimap.Databases.Engine;
import ch.fmartin.outlooktoimap.config.Config.Database;
import ch.fmartin.outlooktoimap.files.PrivateFiles;
import ch.fmartin.outlooktoimap.store.Store.AccountStatus;
import ch.fmartin.outlooktoimap.store.Store.Checkpoint;
import ch.fmartin.outlooktoimap.store.Store.Receipt;
import ch.fmartin.outlooktoimap.store.Store.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class StoreTest {

    private static final Instant T0 = Instant.parse("2026-10-26T10:00:00Z");
    private static final String LONG_IMMUTABLE_ID =
            "AAkALgAAAAAAHYQDEapmEc2byACqAC-EWg0AH4iV7CU7Bkqf3mlUeTBQQgAAAhoyKwAA" + "x".repeat(100);

    @TempDir
    Path directory;

    static java.util.stream.Stream<Engine> engines() {
        return Databases.engines();
    }

    @ParameterizedTest
    @MethodSource("engines")
    void aReceiptGoesFromPendingToImportedToRemovedAndSurvivesAReopen(Engine engine) {
        Database database = Databases.fresh(engine, directory);
        try (Store store = Store.open(database)) {
            assertThat(store.receipt("hotmail", LONG_IMMUTABLE_ID)).isEmpty();
            store.savePending(pending(LONG_IMMUTABLE_ID));
            assertThat(store.receipt("hotmail", LONG_IMMUTABLE_ID)).get().extracting(Receipt::state).isEqualTo(State.PENDING);

            store.recordError("hotmail", LONG_IMMUTABLE_ID, "x".repeat(5000));
            assertThat(store.receipt("hotmail", LONG_IMMUTABLE_ID).get().lastError()).hasSize(2000);

            store.markImported("hotmail", LONG_IMMUTABLE_ID, 42L, T0.plusSeconds(1));
        }
        // A restart reopens the database; Flyway sees the schema is current and leaves it alone.
        try (Store store = Store.open(database)) {
            Receipt imported = store.receipt("hotmail", LONG_IMMUTABLE_ID).get();
            assertThat(imported.state()).isEqualTo(State.IMPORTED);
            assertThat(imported.destinationUid()).isEqualTo(42L);
            assertThat(imported.importedAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(imported.lastError()).isNull();
            assertThat(imported.contentHash()).isEqualTo("a".repeat(64));

            store.markRemoved("hotmail", LONG_IMMUTABLE_ID, T0.plusSeconds(2));
            Receipt removed = store.receipt("hotmail", LONG_IMMUTABLE_ID).get();
            assertThat(removed.state()).isEqualTo(State.REMOVED);
            assertThat(removed.removedAt()).isEqualTo(T0.plusSeconds(2));
            // Receipts are per account: the same id in another account is a different message.
            assertThat(store.receipt("live", LONG_IMMUTABLE_ID)).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void aSecondPendingUploadUpdatesTheReceiptInsteadOfFailing(Engine engine) {
        try (Store store = Store.open(Databases.fresh(engine, directory))) {
            store.savePending(pending("m1"));
            store.savePending(new Receipt("hotmail", "m1", State.PENDING, "junkemail", "other", "Elsewhere", null,
                    "b".repeat(64), 20, null, T0, null, null, null));

            Receipt receipt = store.receipt("hotmail", "m1").get();
            assertThat(receipt.destination()).isEqualTo("other");
            assertThat(receipt.destinationUid()).isNull();
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void aMessageIdLongerThanItsColumnIsCutInsteadOfRefused(Engine engine) {
        // A sender controls the Message-ID; PostgreSQL would refuse the receipt and block the folder.
        String longMessageId = "<" + "x".repeat(3000) + "@spam.example>";
        try (Store store = Store.open(Databases.fresh(engine, directory))) {
            store.savePending(new Receipt("hotmail", "m1", State.PENDING, "junkemail", "fastmail", "Outlook Junk",
                    longMessageId, "a".repeat(64), 1234, null, T0, null, null, null));

            assertThat(store.receipt("hotmail", "m1").get().internetMessageId()).isEqualTo(longMessageId.substring(0, Store.MAX_MESSAGE_ID_LENGTH));
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void aCutNeverSplitsACharacterThatTakesTwoJavaChars(Engine engine) {
        // The emoji takes the column's last position and the one after it, so a cut at the column's
        // length would keep only its first half.
        String start = "<" + "x".repeat(Store.MAX_MESSAGE_ID_LENGTH - 2);
        String longMessageId = start + "\uD83D\uDCE7@spam.example>";
        try (Store store = Store.open(Databases.fresh(engine, directory))) {
            store.savePending(new Receipt("hotmail", "m1", State.PENDING, "junkemail", "fastmail", "Outlook Junk",
                    longMessageId, "a".repeat(64), 1234, null, T0, null, null, null));

            assertThat(store.receipt("hotmail", "m1").get().internetMessageId()).isEqualTo(start);
        }
    }

    @Test
    void aNewSqliteDatabaseAndItsDirectoryAreReadableOnlyByTheirOwner() throws Exception {
        assumeTrue(PrivateFiles.supported());
        Path file = directory.resolve("state/outlook-to-imap.db");

        try (Store store = Store.open(new Database(Database.SQLITE_PREFIX + file, null, null))) {
            assertThat(Files.getPosixFilePermissions(file.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void postgresqlNeedsAVerifiedTlsConnectionUnlessTheUrlSaysOtherwise() {
        Database withoutTls = Databases.fresh(Engine.POSTGRESQL, directory);
        Database urlWithoutSslmode = new Database(withoutTls.url().replaceFirst("[?&]" + Databases.TLS_OFF, ""),
                withoutTls.username(), withoutTls.passwordFile());

        // pgjdbc reads sslmode before ssl, so a default sslmode must not override ssl=false.
        Database urlWithSslFalse = new Database(withoutTls.url().replace(Databases.TLS_OFF, "ssl=false"),
                withoutTls.username(), withoutTls.passwordFile());

        assertThatThrownBy(() -> Store.open(urlWithoutSslmode)).hasStackTraceContaining("does not support SSL");
        try (Store store = Store.open(withoutTls)) {
            assertThat(store.statuses()).isEmpty();
        }
        try (Store store = Store.open(urlWithSslFalse)) {
            assertThat(store.statuses()).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void checkpointsArePerAccountAndFolder(Engine engine) {
        try (Store store = Store.open(Databases.fresh(engine, directory))) {
            store.saveCheckpoint("hotmail", "junkemail", new Checkpoint("junkemail", "link-1", false), T0);
            store.saveCheckpoint("hotmail", "junkemail", new Checkpoint("junkemail", "link-2", true), T0);
            store.saveCheckpoint("hotmail", "archive", new Checkpoint("f-archive", null, false), T0);

            assertThat(store.checkpoint("hotmail", "junkemail")).contains(new Checkpoint("junkemail", "link-2", true));
            assertThat(store.checkpoint("hotmail", "archive")).contains(new Checkpoint("f-archive", null, false));
            assertThat(store.checkpoint("live", "junkemail")).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void aCheckpointSavedBeforeFolderIdsReadsNoKnownFolderUntilItsNextSave(Engine engine) throws Exception {
        Database database = Databases.fresh(engine, directory);
        // The SQLite driver creates the file but not its directory, which Store.open would create.
        Files.createDirectories(directory.resolve("state"));
        String password = database.passwordFile() == null ? null : Files.readString(database.passwordFile()).strip();
        Flyway.configure().dataSource(database.url(), database.username(), password)
                .locations("classpath:db/migration").target("1").load().migrate();
        try (Connection connection = DriverManager.getConnection(database.url(), database.username(), password);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO checkpoint (account, source_folder, delta_link, removal_enabled, updated_at)"
                    + " VALUES ('hotmail', 'Projects', 'link-1', FALSE, 0)");
        }

        try (Store store = Store.open(database)) {
            Checkpoint upgraded = store.checkpoint("hotmail", "Projects").get();
            assertThat(upgraded).isEqualTo(new Checkpoint(null, "link-1", false));
            assertThat(upgraded.readsFolder("f-projects")).isFalse();

            store.saveCheckpoint("hotmail", "Projects", new Checkpoint("f-projects", "link-2", false), T0);
            Checkpoint saved = store.checkpoint("hotmail", "Projects").get();
            assertThat(saved.readsFolder("f-projects")).isTrue();
            assertThat(saved.readsFolder("f-new")).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void aFailedPollKeepsTheLastSuccess(Engine engine) {
        try (Store store = Store.open(Databases.fresh(engine, directory))) {
            store.saveStatus(new AccountStatus("hotmail", T0, T0.plusSeconds(5), new Counts(3, 1, 2, 0), null));
            store.saveStatus(new AccountStatus("hotmail", T0.plusSeconds(60), null, new Counts(0, 0, 0, 1), "throttled"));

            AccountStatus status = store.statuses().getFirst();
            assertThat(status.lastAttemptAt()).isEqualTo(T0.plusSeconds(60));
            assertThat(status.lastSuccessAt()).isEqualTo(T0.plusSeconds(5));
            assertThat(status.counts().failed()).isEqualTo(1);
            assertThat(status.lastError()).isEqualTo("throttled");
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void onlyOneHolderGetsTheLeaseUntilItIsReleasedOrExpires(Engine engine) {
        try (Store store = Store.open(Databases.fresh(engine, directory))) {
            assertThat(store.acquireLease("a", T0, T0.plusSeconds(900))).isTrue();
            assertThat(store.acquireLease("b", T0.plusSeconds(1), T0.plusSeconds(901))).isFalse();
            assertThat(store.acquireLease("a", T0.plusSeconds(2), T0.plusSeconds(902))).isTrue();

            store.releaseLease("b");
            assertThat(store.acquireLease("b", T0.plusSeconds(3), T0.plusSeconds(903))).isFalse();
            store.releaseLease("a");
            assertThat(store.acquireLease("b", T0.plusSeconds(4), T0.plusSeconds(904))).isTrue();

            // A holder that died stops renewing; its lease runs out and another can take over.
            assertThat(store.acquireLease("c", T0.plusSeconds(905), T0.plusSeconds(1800))).isTrue();

            // Renewing extends only a lease still held, never a released or taken one.
            assertThat(store.renewLease("c", T0.plusSeconds(2000))).isTrue();
            assertThat(store.renewLease("b", T0.plusSeconds(2000))).isFalse();
            store.releaseLease("c");
            assertThat(store.renewLease("c", T0.plusSeconds(2100))).isFalse();
            assertThat(store.acquireLease("d", T0.plusSeconds(1000), T0.plusSeconds(1900))).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({
        // sqlite-jdbc applies a pragma and drops it from the file name, but keeps anything else.
        "outlook-to-imap.db?journal_mode=WAL, outlook-to-imap.db",
        "outlook-to-imap.db?a=1&journal_mode=WAL&b=2, outlook-to-imap.db?b=2&a=1",
    })
    void aSqliteUrlWithParametersMakesTheFileItOpensPrivate(String address, String fileName) throws Exception {
        assumeTrue(PrivateFiles.supported());
        Path state = directory.resolve("state");

        try (Store store = Store.open(new Database(Database.SQLITE_PREFIX + state.resolve(address), null, null))) {
            assertThat(Files.getPosixFilePermissions(state.resolve(fileName))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static Receipt pending(String id) {
        return new Receipt("hotmail", id, State.PENDING, "junkemail", "fastmail", "Outlook Junk", "<x@example.test>",
                "a".repeat(64), 1234, null, T0, null, null, null);
    }
}
