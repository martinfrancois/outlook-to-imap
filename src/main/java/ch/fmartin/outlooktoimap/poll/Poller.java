package ch.fmartin.outlooktoimap.poll;

import ch.fmartin.outlooktoimap.config.Config;
import ch.fmartin.outlooktoimap.config.Config.Account;
import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.FolderMapping;
import ch.fmartin.outlooktoimap.imap.DestinationConnection;
import ch.fmartin.outlooktoimap.imap.Fingerprint;
import ch.fmartin.outlooktoimap.imap.ImapDestination;
import ch.fmartin.outlooktoimap.imap.ImapDestination.DestinationException;
import ch.fmartin.outlooktoimap.imap.ImapDestination.DestinationUnavailableException;
import ch.fmartin.outlooktoimap.microsoft.GraphSource;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.Changes;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.DeltaExpiredException;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.GraphException;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.MessageGoneException;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.SourceMessage;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.ThrottledException;
import ch.fmartin.outlooktoimap.microsoft.AccessTokens;
import ch.fmartin.outlooktoimap.microsoft.MicrosoftLoginException;
import ch.fmartin.outlooktoimap.microsoft.ReauthorizationRequiredException;
import ch.fmartin.outlooktoimap.store.Store;
import ch.fmartin.outlooktoimap.store.Counts;
import ch.fmartin.outlooktoimap.store.Store.AccountStatus;
import ch.fmartin.outlooktoimap.store.Store.Receipt;
import ch.fmartin.outlooktoimap.store.Store.State;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One poll over every configured account. The receipt of a message decides what happens to it:
 * none means copy it, a pending one means find out whether an earlier upload landed, an imported
 * one means at most remove the source. A failure stays with its message, folder or account and
 * never stops the others.
 */
public final class Poller {

    private static final Logger LOG = LoggerFactory.getLogger(Poller.class);

    private final Config config;
    private final Store store;
    private final AccessTokens tokens;
    private final Function<Account, GraphSource> sources;
    private final Function<Destination, DestinationConnection> destinations;
    private final Clock clock;

    public Poller(Config config, Store store, AccessTokens tokens) {
        this(config, store, tokens, account -> new GraphSource(() -> tokens.accessToken(account)),
                ImapDestination::connect, Clock.systemUTC());
    }

    public Poller(Config config, Store store, AccessTokens tokens, Function<Account, GraphSource> sources,
            Function<Destination, DestinationConnection> destinations, Clock clock) {
        this.config = config;
        this.store = store;
        this.tokens = tokens;
        this.sources = sources;
        this.destinations = destinations;
        this.clock = clock;
    }

    /**
     * Polls every account once.
     *
     * @param dryRun when true, only reports through {@code plan} what a real poll would copy and
     *     remove; nothing is uploaded, moved or recorded
     * @param stop checked between messages, so a shutdown ends the poll at a message boundary
     * @param progress called after every message, used to keep the poll lease alive
     */
    public PollReport poll(boolean dryRun, Consumer<String> plan, BooleanSupplier stop, Runnable progress) {
        List<AccountResult> results = new ArrayList<>();
        boolean interrupted = false;
        try (Connections connections = new Connections()) {
            for (Account account : config.accounts()) {
                if (stop.getAsBoolean()) {
                    interrupted = true;
                    break;
                }
                AccountResult result = new AccountPoll(account, connections, dryRun, plan, stop, progress).execute();
                results.add(result);
                if (!dryRun) {
                    store.saveStatus(new AccountStatus(account.name(), result.startedAt(),
                            result.succeeded() ? clock.instant() : null, result.counts(), result.error()));
                }
            }
        }
        return new PollReport(results, interrupted || results.stream().anyMatch(AccountResult::interrupted));
    }

    /** The destination connections of one poll, opened on first use and closed at the end. */
    private final class Connections implements AutoCloseable {
        private final Map<String, DestinationConnection> open = new HashMap<>();

        DestinationConnection get(String name) {
            return open.computeIfAbsent(name, n -> destinations.apply(config.destinations().get(n)));
        }

        void drop(String name) {
            DestinationConnection connection = open.remove(name);
            if (connection != null) {
                connection.close();
            }
        }

        @Override
        public void close() {
            open.values().forEach(DestinationConnection::close);
            open.clear();
        }
    }

    private final class AccountPoll {
        private final Account account;
        private final Connections connections;
        private final boolean dryRun;
        private final Consumer<String> plan;
        private final BooleanSupplier stop;
        private final Runnable progress;
        private final Instant startedAt = clock.instant();
        private int imported;
        private int removed;
        private int skipped;
        private int failed;
        private final List<String> errors = new ArrayList<>();
        private boolean interrupted;

        AccountPoll(Account account, Connections connections, boolean dryRun, Consumer<String> plan,
                BooleanSupplier stop, Runnable progress) {
            this.account = account;
            this.connections = connections;
            this.dryRun = dryRun;
            this.plan = plan;
            this.stop = stop;
            this.progress = progress;
        }

        AccountResult execute() {
            try {
                // Its result is unused: it makes a missing login fail the account before any folder work, see docs/adr/0016-monitoring-and-failure-isolation.md
                tokens.accessToken(account);
                GraphSource source = sources.apply(account);
                for (FolderMapping mapping : account.folders()) {
                    if (stop.getAsBoolean()) {
                        interrupted = true;
                        break;
                    }
                    try {
                        copyFolder(source, mapping);
                    } catch (DestinationUnavailableException e) {
                        connections.drop(e.destination());
                        fail(mapping.source() + ": " + e.getMessage(), e);
                    } catch (ThrottledException | ReauthorizationRequiredException e) {
                        // Throttling or a lost login ends this account's poll, since every remaining folder would fail the same way, see docs/adr/0016-monitoring-and-failure-isolation.md
                        throw e;
                    } catch (GraphException | DestinationException e) {
                        fail(mapping.source() + ": " + e.getMessage(), e);
                    }
                }
            } catch (ReauthorizationRequiredException e) {
                fail(e.getMessage(), null);
            } catch (ThrottledException e) {
                fail(e.getMessage(), null);
            } catch (RuntimeException e) {
                fail(unexpectedFailure(e), e);
            }
            if (interrupted) {
                errors.add("poll interrupted before every folder was read");
            }
            return new AccountResult(account.name(), startedAt, new Counts(imported, removed, skipped, failed),
                    errors.isEmpty() ? null : String.join("; ", errors), interrupted);
        }

        private void fail(String message, Throwable cause) {
            failed++;
            errors.add(message);
            LOG.warn("account {}: {}", account.name(), message, cause);
        }

        private void copyFolder(GraphSource source, FolderMapping mapping) {
            String folderId = source.resolveFolder(mapping.source());
            Optional<Store.Checkpoint> checkpoint = store.checkpoint(account.name(), mapping.source());
            String deltaLink = checkpoint.map(Store.Checkpoint::deltaLink).orElse(null);
            if (mapping.removeSourceAfterSync() && checkpoint.map(c -> !c.removalEnabled()).orElse(false)) {
                // Without this reread, messages copied while removal was off would stay in the source, see docs/adr/0004-one-copy-per-account-and-removal-trusts-the-receipt.md
                LOG.info("account {}: removal switched on for {}, rereading the whole folder", account.name(), mapping.source());
                deltaLink = null;
            }
            // Without this reread, the poll would keep reading a deleted folder and never the new one with its name, see docs/adr/0027-a-checkpoint-remembers-the-folder-its-delta-link-reads.md
            if (checkpoint.map(c -> !c.readsFolder(folderId)).orElse(false)) {
                LOG.info("account {}: {} is not known to be the folder its checkpoint reads, rereading the whole folder",
                        account.name(), mapping.source());
                deltaLink = null;
            }
            Changes changes;
            try {
                changes = source.changes(folderId, deltaLink);
            } catch (DeltaExpiredException e) {
                LOG.info("account {}: {}: {}, rereading the whole folder", account.name(), mapping.source(), e.getMessage());
                changes = source.changes(folderId, null);
            }
            int failedBefore = failed;
            for (SourceMessage message : changes.messages()) {
                if (stop.getAsBoolean()) {
                    interrupted = true;
                    break;
                }
                try {
                    copyMessage(source, mapping, message);
                } catch (MessageGoneException e) {
                    skipped++;
                } catch (ThrottledException | DestinationUnavailableException | ReauthorizationRequiredException
                        | MicrosoftLoginException e) {
                    throw e;
                } catch (GraphException | DestinationException e) {
                    failMessage(mapping, message, e.getMessage(), e);
                } catch (RuntimeException e) {
                    // A download that breaks off or a value the database refuses concerns this message only.
                    failMessage(mapping, message, unexpectedFailure(e), e);
                }
                progress.run();
            }
            // Saving after a failed message would start the next round past it, and the next poll would not see it again, see docs/adr/0010-read-any-source-folder-through-graph-delta-queries.md
            // Everything that already succeeded is skipped by its receipt.
            if (!dryRun && !interrupted && failed == failedBefore) {
                store.saveCheckpoint(account.name(), mapping.source(),
                        new Store.Checkpoint(folderId, changes.deltaLink(), mapping.removeSourceAfterSync()), clock.instant());
            }
        }

        private void failMessage(FolderMapping mapping, SourceMessage message, String error, RuntimeException cause) {
            fail(mapping.source() + ": message " + message.id() + ": " + error, cause);
            if (!dryRun) {
                store.recordError(account.name(), message.id(), error);
            }
        }

        private void copyMessage(GraphSource source, FolderMapping mapping, SourceMessage message) {
            Optional<Receipt> receipt = store.receipt(account.name(), message.id());
            State state = receipt.map(Receipt::state).orElse(null);
            // Uploading an IMPORTED or REMOVED message again would bring back copies the user deleted at the destination, see docs/adr/0004-one-copy-per-account-and-removal-trusts-the-receipt.md
            if (state == State.REMOVED || (state == State.IMPORTED && !mapping.removeSourceAfterSync())) {
                skipped++;
                return;
            }
            if (dryRun) {
                if (state == null) {
                    plan.accept(describe("copy", mapping, message));
                } else if (state == State.PENDING) {
                    plan.accept(describe("check, then copy if missing", mapping, message));
                }
                if (mapping.removeSourceAfterSync()) {
                    plan.accept(describe("remove from the source", mapping, message));
                }
                return;
            }
            if (state != State.IMPORTED) {
                upload(source, mapping, message, receipt.orElse(null));
            }
            // Reached only once the destination shows the copy; removing any earlier could lose a message whose upload failed, see docs/adr/0004-one-copy-per-account-and-removal-trusts-the-receipt.md
            if (mapping.removeSourceAfterSync()) {
                try {
                    source.moveToDeletedItems(message.id());
                } catch (MessageGoneException e) {
                    LOG.debug("account {}: message {} was already gone from the source", account.name(), message.id());
                }
                store.markRemoved(account.name(), message.id(), clock.instant());
                removed++;
            }
        }

        private void upload(GraphSource source, FolderMapping mapping, SourceMessage message, Receipt pending) {
            byte[] mime = Fingerprint.canonical(source.mime(message.id()));
            Fingerprint fingerprint = Fingerprint.of(mime);
            if (pending != null && foundEarlierUpload(pending, fingerprint)) {
                imported++;
                return;
            }
            DestinationConnection destination = connections.get(mapping.destination());
            // Without a PENDING receipt before the first byte, an upload whose reply was lost would be uploaded again, see docs/adr/0014-verify-each-upload-and-settle-unknown-ones-by-content.md
            store.savePending(new Receipt(account.name(), message.id(), State.PENDING, mapping.source(), mapping.destination(),
                    mapping.destinationFolder(), message.internetMessageId(), fingerprint.contentHash(), fingerprint.size(), null,
                    clock.instant(), null, null, null));
            Date received = message.receivedDateTime() == null ? null : Date.from(message.receivedDateTime().toInstant());
            OptionalLong uid = destination.append(mapping.destinationFolder(), mime, received);
            // Trusting the server's OK instead of the listed copy could remove a source message whose copy is not there, see docs/adr/0014-verify-each-upload-and-settle-unknown-ones-by-content.md
            OptionalLong stored = uid.isPresent()
                    ? (destination.contains(mapping.destinationFolder(), uid.getAsLong()) ? uid : OptionalLong.empty())
                    : destination.find(mapping.destinationFolder(), fingerprint);
            long storedUid = stored.orElseThrow(() -> new DestinationException("destination " + mapping.destination()
                    + " accepted the upload but the message is not in " + mapping.destinationFolder(), null));
            store.markImported(account.name(), message.id(), storedUid, clock.instant());
            imported++;
        }

        /**
         * Looks for the copy an upload without a known outcome may have stored, where that upload
         * went and with the content recorded before it started. Microsoft may serve a message's
         * bytes slightly differently the second time, so a fresh download would not match.
         */
        private boolean foundEarlierUpload(Receipt pending, Fingerprint current) {
            if (!config.destinations().containsKey(pending.destination())) {
                LOG.warn("account {}: message {} may already be at destination {}, which is no longer configured;"
                        + " copying it again", account.name(), pending.messageId(), pending.destination());
                return false;
            }
            OptionalLong found;
            try {
                // A fresh download's hash would miss a copy whose bytes Microsoft now serves differently, and upload it twice, see docs/adr/0014-verify-each-upload-and-settle-unknown-ones-by-content.md
                found = connections.get(pending.destination())
                        .find(pending.destinationFolder(), current.withContent(pending.contentHash(), pending.sizeBytes()));
            } catch (DestinationUnavailableException e) {
                // Only this message depends on that destination; the folder's own one may be fine.
                connections.drop(e.destination());
                throw new DestinationException("cannot check " + e.destination() + " for an earlier upload: "
                        + e.getMessage(), e);
            }
            found.ifPresent(uid -> store.markImported(account.name(), pending.messageId(), uid, clock.instant()));
            return found.isPresent();
        }

        private String describe(String action, FolderMapping mapping, SourceMessage message) {
            return account.name() + " " + mapping.source() + " -> " + mapping.destination() + "/" + mapping.destinationFolder() + ": "
                    + action + " message received " + message.receivedDateTime()
                    + (message.internetMessageId() == null ? "" : " " + message.internetMessageId());
        }
    }

    private static String unexpectedFailure(RuntimeException e) {
        return "unexpected failure: " + e;
    }
}
