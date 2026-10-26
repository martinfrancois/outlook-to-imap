package ch.fmartin.outlooktoimap.store;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.outlooktoimap.Databases;
import ch.fmartin.outlooktoimap.Databases.Engine;
import ch.fmartin.outlooktoimap.config.Config.Database;
import ch.fmartin.outlooktoimap.store.Store.AccountStatus;
import ch.fmartin.outlooktoimap.store.Store.Checkpoint;
import ch.fmartin.outlooktoimap.store.Store.Receipt;
import ch.fmartin.outlooktoimap.store.Store.State;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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
    void checkpointsArePerAccountAndFolder(Engine engine) {
        try (Store store = Store.open(Databases.fresh(engine, directory))) {
            store.saveCheckpoint("hotmail", "junkemail", "link-1", false, T0);
            store.saveCheckpoint("hotmail", "junkemail", "link-2", true, T0);
            store.saveCheckpoint("hotmail", "archive", null, false, T0);

            assertThat(store.checkpoint("hotmail", "junkemail")).contains(new Checkpoint("link-2", true));
            assertThat(store.checkpoint("hotmail", "archive")).contains(new Checkpoint(null, false));
            assertThat(store.checkpoint("live", "junkemail")).isEmpty();
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
        }
    }

    private static Receipt pending(String id) {
        return new Receipt("hotmail", id, State.PENDING, "junkemail", "fastmail", "Outlook Junk", "<x@example.test>",
                "a".repeat(64), 1234, null, T0, null, null, null);
    }
}
