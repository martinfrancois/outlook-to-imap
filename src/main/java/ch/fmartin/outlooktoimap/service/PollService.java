package ch.fmartin.outlooktoimap.service;

import ch.fmartin.outlooktoimap.sync.RunReport;
import ch.fmartin.outlooktoimap.sync.Synchronizer;
import ch.fmartin.outlooktoimap.store.Store;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls right away and then once per interval, one poll at a time. A database lease keeps a
 * second process that shares the database, such as a manual {@code once}, from polling at the
 * same time. Stopping lets the current message finish, then ends the poll.
 */
public final class PollService {

    private static final Logger LOG = LoggerFactory.getLogger(PollService.class);
    // Renewed after every message, so it only runs out when the holder died.
    private static final Duration LEASE = Duration.ofMinutes(15);

    private final Synchronizer synchronizer;
    private final Store store;
    private final Heartbeat heartbeat;
    private final Duration interval;
    private final Clock clock;
    private final String holder;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<Instant> lastFinished = new AtomicReference<>();
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>();
    private ScheduledExecutorService executor;

    public PollService(Synchronizer synchronizer, Store store, Heartbeat heartbeat, Duration interval, Clock clock) {
        this.synchronizer = synchronizer;
        this.store = store;
        this.heartbeat = heartbeat;
        this.interval = interval;
        this.clock = clock;
        this.holder = hostName() + "/" + UUID.randomUUID();
        this.lastActivity.set(clock.instant());
    }

    /** Runs one poll if no other process holds the lease. Empty when it was held. */
    public Optional<RunReport> pollOnce(boolean dryRun, Consumer<String> plan) {
        if (dryRun) {
            return Optional.of(synchronizer.poll(true, plan, stopping::get, () -> {}));
        }
        if (!store.acquireLease(holder, clock.instant(), clock.instant().plus(LEASE))) {
            LOG.warn("another process is polling with the same database; skipping this poll");
            return Optional.empty();
        }
        try {
            RunReport report = synchronizer.poll(false, plan, stopping::get, () -> {
                lastActivity.set(clock.instant());
                store.acquireLease(holder, clock.instant(), clock.instant().plus(LEASE));
            });
            lastFinished.set(clock.instant());
            lastActivity.set(clock.instant());
            heartbeat.report(report);
            return Optional.of(report);
        } finally {
            store.releaseLease(holder);
        }
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "poll");
            t.setDaemon(false);
            return t;
        });
        // Fixed delay counts from the end of a poll, so a long poll never overlaps the next one.
        executor.scheduleWithFixedDelay(() -> {
            try {
                pollOnce(false, line -> {}).ifPresent(report -> LOG.info("poll finished\n{}", report.summary()));
            } catch (RuntimeException e) {
                LOG.error("poll failed", e);
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("polling every {}", interval);
    }

    /** Asks the current poll to stop at the next message boundary and waits for it. */
    public void stop(Duration grace) {
        stopping.set(true);
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("poll did not stop within {}; exiting anyway, the next start reconciles it", grace);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Healthy while polls make progress: a poll finished, or a message was handled, less than two
     * intervals plus a margin ago. A long first poll through a big backlog stays healthy because
     * every message counts. Account failures do not count here; they go to the heartbeat, because
     * restarting the container would not fix them.
     */
    public boolean healthy() {
        if (stopping.get() || executor == null || executor.isShutdown()) {
            return false;
        }
        Duration allowed = interval.multipliedBy(2).plus(LEASE);
        return Duration.between(lastActivity.get(), clock.instant()).compareTo(allowed) < 0;
    }

    public Instant lastFinished() {
        return lastFinished.get();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown-host";
        }
    }
}
