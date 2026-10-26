package ch.fmartin.outlooktoimap.sync;

import ch.fmartin.outlooktoimap.config.Config;
import ch.fmartin.outlooktoimap.config.Config.Account;
import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.FolderMapping;
import ch.fmartin.outlooktoimap.imap.DestinationMailbox;
import ch.fmartin.outlooktoimap.imap.ImapDestination;
import ch.fmartin.outlooktoimap.imap.ImapDestination.DestinationException;
import ch.fmartin.outlooktoimap.imap.ImapDestination.DestinationUnavailableException;
import ch.fmartin.outlooktoimap.microsoft.GraphMailbox;
import ch.fmartin.outlooktoimap.microsoft.GraphMailbox.Changes;
import ch.fmartin.outlooktoimap.microsoft.GraphMailbox.DeltaExpiredException;
import ch.fmartin.outlooktoimap.microsoft.GraphMailbox.GraphException;
import ch.fmartin.outlooktoimap.microsoft.GraphMailbox.MessageGoneException;
import ch.fmartin.outlooktoimap.microsoft.GraphMailbox.SourceMessage;
import ch.fmartin.outlooktoimap.microsoft.GraphMailbox.ThrottledException;
import ch.fmartin.outlooktoimap.microsoft.AccessTokens;
import ch.fmartin.outlooktoimap.microsoft.ReauthorizationRequiredException;
import ch.fmartin.outlooktoimap.store.Store;
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
public final class Synchronizer {

    private static final Logger LOG = LoggerFactory.getLogger(Synchronizer.class);

    private final Config config;
    private final Store store;
    private final AccessTokens login;
    private final Function<Account, GraphMailbox> mailboxes;
    private final Function<Destination, DestinationMailbox> destinations;
    private final Clock clock;

    public Synchronizer(Config config, Store store, AccessTokens login) {
        this(config, store, login, account -> new GraphMailbox(() -> login.accessToken(account)),
                ImapDestination::connect, Clock.systemUTC());
    }

    public Synchronizer(Config config, Store store, AccessTokens login, Function<Account, GraphMailbox> mailboxes,
            Function<Destination, DestinationMailbox> destinations, Clock clock) {
        this.config = config;
        this.store = store;
        this.login = login;
        this.mailboxes = mailboxes;
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
    public RunReport poll(boolean dryRun, Consumer<String> plan, BooleanSupplier stop, Runnable progress) {
        List<AccountResult> results = new ArrayList<>();
        try (Connections connections = new Connections()) {
            for (Account account : config.accounts()) {
                if (stop.getAsBoolean()) {
                    break;
                }
                AccountRun run = new AccountRun(account, connections, dryRun, plan, stop, progress);
                AccountResult result = run.execute();
                results.add(result);
                if (!dryRun) {
                    store.saveStatus(new AccountStatus(account.name(), result.startedAt(),
                            result.succeeded() ? clock.instant() : null, result.imported(), result.removed(),
                            result.skipped(), result.failed(), result.error()));
                }
            }
        }
        return new RunReport(results);
    }

    /** The destination connections of one poll, opened on first use and closed at the end. */
    private final class Connections implements AutoCloseable {
        private final Map<String, DestinationMailbox> open = new HashMap<>();

        DestinationMailbox get(String name) {
            return open.computeIfAbsent(name, n -> {
                Destination destination = config.destinations().get(n);
                if (destination == null) {
                    throw new DestinationException("destination " + n + " is no longer configured", null);
                }
                return destinations.apply(destination);
            });
        }

        void drop(String name) {
            DestinationMailbox mailbox = open.remove(name);
            if (mailbox != null) {
                mailbox.close();
            }
        }

        @Override
        public void close() {
            open.values().forEach(DestinationMailbox::close);
            open.clear();
        }
    }

    private final class AccountRun {
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

        AccountRun(Account account, Connections connections, boolean dryRun, Consumer<String> plan,
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
                // Fails fast with a clear message when the login is gone, before any folder work.
                login.accessToken(account);
                GraphMailbox mailbox = mailboxes.apply(account);
                for (FolderMapping mapping : account.folders()) {
                    if (stop.getAsBoolean()) {
                        break;
                    }
                    try {
                        copyFolder(mailbox, mapping);
                    } catch (DestinationUnavailableException e) {
                        connections.drop(mapping.destination());
                        fail(mapping.source() + ": " + e.getMessage(), e);
                    } catch (ThrottledException | ReauthorizationRequiredException e) {
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
                fail("unexpected failure: " + e, e);
            }
            return new AccountResult(account.name(), startedAt, imported, removed, skipped, failed,
                    errors.isEmpty() ? null : String.join("; ", errors));
        }

        private void fail(String message, Throwable cause) {
            failed++;
            errors.add(message);
            LOG.warn("account {}: {}", account.name(), message, cause);
        }

        private void copyFolder(GraphMailbox mailbox, FolderMapping mapping) {
            String folderId = mailbox.resolveFolder(mapping.source());
            Optional<Store.Checkpoint> checkpoint = store.checkpoint(account.name(), mapping.source());
            String deltaLink = checkpoint.map(Store.Checkpoint::deltaLink).orElse(null);
            if (mapping.removeSourceAfterSync() && checkpoint.map(c -> !c.removalEnabled()).orElse(false)) {
                // Removal was just switched on: list the folder from the start, so messages copied
                // while it was off leave the source too.
                LOG.info("account {}: removal switched on for {}, rereading the whole folder", account.name(), mapping.source());
                deltaLink = null;
            }
            Changes changes;
            try {
                changes = mailbox.changes(folderId, deltaLink);
            } catch (DeltaExpiredException e) {
                LOG.info("account {}: delta link for {} expired, rereading the whole folder", account.name(), mapping.source());
                changes = mailbox.changes(folderId, null);
            }
            int failedBefore = failed;
            boolean interrupted = false;
            for (SourceMessage message : changes.messages()) {
                if (stop.getAsBoolean()) {
                    interrupted = true;
                    break;
                }
                try {
                    copyMessage(mailbox, mapping, message);
                } catch (MessageGoneException e) {
                    skipped++;
                } catch (ThrottledException | DestinationUnavailableException e) {
                    throw e;
                } catch (GraphException | DestinationException e) {
                    failed++;
                    String error = mapping.source() + ": message " + message.id() + ": " + e.getMessage();
                    errors.add(error);
                    LOG.warn("account {}: {}", account.name(), error, e);
                    if (!dryRun) {
                        store.recordError(account.name(), message.id(), e.getMessage());
                    }
                }
                progress.run();
            }
            // A failed message keeps the checkpoint where it was, so the next poll sees the message
            // again. Everything that already succeeded is skipped by its receipt.
            if (!dryRun && !interrupted && failed == failedBefore) {
                store.saveCheckpoint(account.name(), mapping.source(), changes.deltaLink(), mapping.removeSourceAfterSync(),
                        clock.instant());
            }
        }

        private void copyMessage(GraphMailbox mailbox, FolderMapping mapping, SourceMessage message) {
            Optional<Receipt> receipt = store.receipt(account.name(), message.id());
            State state = receipt.map(Receipt::state).orElse(null);
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
                upload(mailbox, mapping, message, receipt.orElse(null));
            }
            if (mapping.removeSourceAfterSync()) {
                try {
                    mailbox.moveToDeletedItems(message.id());
                } catch (MessageGoneException e) {
                    LOG.debug("account {}: message {} was already gone from the source", account.name(), message.id());
                }
                store.markRemoved(account.name(), message.id(), clock.instant());
                removed++;
            }
        }

        private void upload(GraphMailbox mailbox, FolderMapping mapping, SourceMessage message, Receipt pending) {
            byte[] mime = ImapDestination.canonical(mailbox.mime(message.id()));
            // An earlier upload without a known outcome is checked against the destination it went
            // to, which may differ from the current configuration.
            String destinationName = pending != null ? pending.destination() : mapping.destination();
            String folder = pending != null ? pending.destinationFolder() : mapping.folder();
            DestinationMailbox destination = connections.get(destinationName);
            if (pending != null) {
                OptionalLong found = destination.find(folder, mime);
                if (found.isPresent()) {
                    store.markImported(account.name(), message.id(), found.getAsLong(), clock.instant());
                    imported++;
                    return;
                }
            }
            store.savePending(new Receipt(account.name(), message.id(), State.PENDING, mapping.source(), destinationName,
                    folder, message.internetMessageId(), ImapDestination.sha256(mime), mime.length, null,
                    clock.instant(), null, null, null));
            Date received = message.receivedDateTime() == null ? null : Date.from(message.receivedDateTime().toInstant());
            OptionalLong uid = destination.append(folder, mime, received);
            // The receipt only says IMPORTED once the server shows the copy, not when it says OK.
            OptionalLong stored = uid.isPresent()
                    ? (destination.contains(folder, uid.getAsLong()) ? uid : OptionalLong.empty())
                    : destination.find(folder, mime);
            if (stored.isEmpty()) {
                throw new DestinationException("destination " + destinationName + " accepted the upload but the message is not in "
                        + folder, null);
            }
            store.markImported(account.name(), message.id(), stored.getAsLong(), clock.instant());
            imported++;
        }

        private String describe(String action, FolderMapping mapping, SourceMessage message) {
            return account.name() + " " + mapping.source() + " -> " + mapping.destination() + "/" + mapping.folder() + ": "
                    + action + " message received " + message.receivedDateTime()
                    + (message.internetMessageId() == null ? "" : " " + message.internetMessageId());
        }
    }
}
