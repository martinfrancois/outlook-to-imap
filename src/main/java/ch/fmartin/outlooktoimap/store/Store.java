package ch.fmartin.outlooktoimap.store;

import ch.fmartin.outlooktoimap.config.Config.Database;
import ch.fmartin.outlooktoimap.config.SecretFile;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;

/**
 * Receipts, checkpoints and per-account status in SQLite or PostgreSQL. Flyway creates and
 * upgrades the schema; every statement here is SQL both engines accept, so neither has code of
 * its own.
 */
public final class Store implements AutoCloseable {

    public enum State { PENDING, IMPORTED, REMOVED }

    public record Receipt(
            String account,
            String messageId,
            State state,
            String sourceFolder,
            String destination,
            String destinationFolder,
            String internetMessageId,
            String contentHash,
            long sizeBytes,
            Long destinationUid,
            Instant createdAt,
            Instant importedAt,
            Instant removedAt,
            String lastError) {}

    public record Checkpoint(String deltaLink, boolean removalEnabled) {}

    public record AccountStatus(
            String account,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Counts counts,
            String lastError) {}

    // Keeps a pathological error message from bloating the database; the columns are TEXT.
    private static final int MAX_ERROR_LENGTH = 2000;
    // The length of receipt.internet_message_id, VARCHAR(2000) in the released migration
    // V1__receipts_checkpoints_status.sql. The column is for people reading the database; nothing
    // matches on it, so a sender's over-long Message-ID is cut instead of refused.
    static final int MAX_MESSAGE_ID_LENGTH = 2000;

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    private Store(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbi = Jdbi.create(dataSource);
    }

    /** Connects, then creates or upgrades the schema. */
    public static Store open(Database database) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(database.url());
        hikari.setPoolName("outlook-to-imap");
        if (database.isSqlite()) {
            createParentDirectory(database.url().substring(Database.SQLITE_PREFIX.length()));
            // SQLite locks the whole file per write; waiting beats failing when the health
            // endpoint reads while a poll writes.
            hikari.addDataSourceProperty("busy_timeout", "30000");
            hikari.setMaximumPoolSize(2);
        } else {
            hikari.setMaximumPoolSize(4);
        }
        if (database.username() != null) {
            hikari.setUsername(database.username());
        }
        if (database.passwordFile() != null) {
            hikari.setPassword(SecretFile.read(database.passwordFile()));
        }
        HikariDataSource dataSource = new HikariDataSource(hikari);
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new Store(dataSource);
    }

    private static void createParentDirectory(String file) {
        if (file.isEmpty() || file.startsWith(":memory:") || file.startsWith("file:")) {
            return;
        }
        Path parent = Path.of(file).toAbsolutePath().getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create database directory " + parent, e);
        }
    }

    public Optional<Receipt> receipt(String account, String messageId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM receipt WHERE account = :account AND message_id = :id")
                .bind("account", account)
                .bind("id", messageId)
                .map((rs, ctx) -> receipt(rs))
                .findOne());
    }

    /** Records that an upload is about to start, before a single byte goes to the destination. */
    public void savePending(Receipt receipt) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO receipt (account, message_id, state, source_folder, destination, destination_folder,
                                             internet_message_id, content_hash, size_bytes, created_at)
                        VALUES (:account, :id, 'PENDING', :source, :destination, :folder, :imid, :hash, :size, :now)
                        ON CONFLICT (account, message_id) DO UPDATE SET
                            source_folder = :source, destination = :destination, destination_folder = :folder,
                            internet_message_id = :imid, content_hash = :hash, size_bytes = :size
                        """)
                .bind("account", receipt.account())
                .bind("id", receipt.messageId())
                .bind("source", receipt.sourceFolder())
                .bind("destination", receipt.destination())
                .bind("folder", receipt.destinationFolder())
                .bind("imid", truncate(receipt.internetMessageId(), MAX_MESSAGE_ID_LENGTH))
                .bind("hash", receipt.contentHash())
                .bind("size", receipt.sizeBytes())
                .bind("now", receipt.createdAt().toEpochMilli())
                .execute());
    }

    public void markImported(String account, String messageId, Long destinationUid, Instant at) {
        update("UPDATE receipt SET state = 'IMPORTED', destination_uid = :uid, imported_at = :at, last_error = NULL "
                + "WHERE account = :account AND message_id = :id", account, messageId, destinationUid, at);
    }

    public void markRemoved(String account, String messageId, Instant at) {
        update("UPDATE receipt SET state = 'REMOVED', removed_at = :at, last_error = NULL "
                + "WHERE account = :account AND message_id = :id", account, messageId, null, at);
    }

    private void update(String sql, String account, String messageId, Long uid, Instant at) {
        jdbi.useHandle(h -> h.createUpdate(sql)
                .bind("account", account)
                .bind("id", messageId)
                .bind("uid", uid)
                .bind("at", at.toEpochMilli())
                .execute());
    }

    public void recordError(String account, String messageId, String error) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE receipt SET last_error = :error WHERE account = :account AND message_id = :id")
                .bind("account", account)
                .bind("id", messageId)
                .bind("error", truncate(error, MAX_ERROR_LENGTH))
                .execute());
    }

    public Optional<Checkpoint> checkpoint(String account, String sourceFolder) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT delta_link, removal_enabled FROM checkpoint WHERE account = :account AND source_folder = :source")
                .bind("account", account)
                .bind("source", sourceFolder)
                .map((rs, ctx) -> new Checkpoint(rs.getString("delta_link"), rs.getBoolean("removal_enabled")))
                .findOne());
    }

    public void saveCheckpoint(String account, String sourceFolder, String deltaLink, boolean removalEnabled, Instant at) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO checkpoint (account, source_folder, delta_link, removal_enabled, updated_at)
                        VALUES (:account, :source, :link, :removal, :at)
                        ON CONFLICT (account, source_folder) DO UPDATE SET
                            delta_link = :link, removal_enabled = :removal, updated_at = :at
                        """)
                .bind("account", account)
                .bind("source", sourceFolder)
                .bind("link", deltaLink)
                .bind("removal", removalEnabled)
                .bind("at", at.toEpochMilli())
                .execute());
    }

    /** Stores the outcome of one account's poll; {@code lastSuccessAt} only moves on success. */
    public void saveStatus(AccountStatus status) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO account_status (account, last_attempt_at, last_success_at, imported, removed, skipped, failed, last_error)
                        VALUES (:account, :attempt, :success, :imported, :removed, :skipped, :failed, :error)
                        ON CONFLICT (account) DO UPDATE SET
                            last_attempt_at = :attempt,
                            last_success_at = COALESCE(:success, account_status.last_success_at),
                            imported = :imported, removed = :removed, skipped = :skipped, failed = :failed,
                            last_error = :error
                        """)
                .bind("account", status.account())
                .bind("attempt", status.lastAttemptAt().toEpochMilli())
                .bind("success", status.lastSuccessAt() == null ? null : status.lastSuccessAt().toEpochMilli())
                .bind("imported", status.counts().imported())
                .bind("removed", status.counts().removed())
                .bind("skipped", status.counts().skipped())
                .bind("failed", status.counts().failed())
                .bind("error", truncate(status.lastError(), MAX_ERROR_LENGTH))
                .execute());
    }

    public List<AccountStatus> statuses() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM account_status ORDER BY account")
                .map((rs, ctx) -> new AccountStatus(
                        rs.getString("account"),
                        instant(rs, "last_attempt_at"),
                        instant(rs, "last_success_at"),
                        new Counts(rs.getInt("imported"), rs.getInt("removed"), rs.getInt("skipped"), rs.getInt("failed")),
                        rs.getString("last_error")))
                .list());
    }

    /**
     * Takes or extends the poll lease. Returns false while another holder's lease is still valid.
     */
    public boolean acquireLease(String holder, Instant now, Instant until) {
        return jdbi.withHandle(h -> h.createUpdate("""
                        UPDATE poll_lease SET holder = :holder, expires_at = :until
                        WHERE id = 1 AND (holder IS NULL OR holder = :holder OR expires_at < :now)
                        """)
                .bind("holder", holder)
                .bind("until", until.toEpochMilli())
                .bind("now", now.toEpochMilli())
                .execute()) == 1;
    }

    /**
     * Extends the poll lease only while {@code holder} still has it. Unlike {@link #acquireLease},
     * it never takes a lease that was released or taken over meanwhile.
     */
    public boolean renewLease(String holder, Instant until) {
        return jdbi.withHandle(h -> h.createUpdate("UPDATE poll_lease SET expires_at = :until WHERE id = 1 AND holder = :holder")
                .bind("holder", holder)
                .bind("until", until.toEpochMilli())
                .execute()) == 1;
    }

    public void releaseLease(String holder) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE poll_lease SET holder = NULL, expires_at = NULL WHERE id = 1 AND holder = :holder")
                .bind("holder", holder)
                .execute());
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static Receipt receipt(ResultSet rs) throws SQLException {
        long uidValue = rs.getLong("destination_uid");
        Long uid = rs.wasNull() ? null : uidValue;
        return new Receipt(
                rs.getString("account"),
                rs.getString("message_id"),
                State.valueOf(rs.getString("state")),
                rs.getString("source_folder"),
                rs.getString("destination"),
                rs.getString("destination_folder"),
                rs.getString("internet_message_id"),
                rs.getString("content_hash"),
                rs.getLong("size_bytes"),
                uid,
                instant(rs, "created_at"),
                instant(rs, "imported_at"),
                instant(rs, "removed_at"),
                rs.getString("last_error"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        long millis = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(millis);
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
