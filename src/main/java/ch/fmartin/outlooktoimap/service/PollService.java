package ch.fmartin.outlooktoimap.service;

import ch.fmartin.outlooktoimap.poll.PollReport;
import ch.fmartin.outlooktoimap.poll.Poller;
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
    // Renewed on a timer and after every message, so it only runs out when the holder died or hung.
    private static final Duration LEASE = Duration.ofMinutes(15);
    private static final Duration LEASE_RENEWAL = LEASE.dividedBy(5);

    private final Poller poller;
    private final Store store;
    private final Heartbeat heartbeat;
    private final Duration interval;
    private final Clock clock;
    private final String holder;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<Instant> lastFinished = new AtomicReference<>();
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>();
    // Read by the health endpoint's thread, which may start before start() assigns it.
    private volatile ScheduledExecutorService executor;

    public PollService(Poller poller, Store store, Heartbeat heartbeat, Duration interval, Clock clock) {
        this.poller = poller;
        this.store = store;
        this.heartbeat = heartbeat;
        this.interval = interval;
        this.clock = clock;
        this.holder = hostName() + "/" + UUID.randomUUID();
        this.lastActivity.set(clock.instant());
    }

    /** Runs one poll if no other process holds the lease. Empty when it was held. */
    public Optional<PollReport> pollOnce(boolean dryRun, Consumer<String> plan) {
        if (dryRun) {
            return Optional.of(poller.poll(true, plan, stopping::get, () -> {}));
        }
        if (!store.acquireLease(holder, clock.instant(), clock.instant().plus(LEASE))) {
            LOG.warn("another process is polling with the same database; skipping this poll");
            return Optional.empty();
        }
        AtomicBoolean leaseLost = new AtomicBoolean();
        Runnable renew = () -> {
            if (!leaseLost.get() && !store.acquireLease(holder, clock.instant(), clock.instant().plus(LEASE))) {
                leaseLost.set(true);
                LOG.error("another process took over the poll lease; stopping this poll at the next message");
            }
        };
        // The timer covers long stretches without messages, such as listing a big folder while
        // Microsoft throttles; renewing after each message covers the rest.
        ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "poll-lease");
            t.setDaemon(true);
            return t;
        });
        renewals.scheduleAtFixedRate(() -> {
            try {
                renew.run();
            } catch (RuntimeException e) {
                LOG.warn("renewing the poll lease failed", e);
            }
        }, LEASE_RENEWAL.toMillis(), LEASE_RENEWAL.toMillis(), TimeUnit.MILLISECONDS);
        try {
            PollReport report = poller.poll(false, plan, () -> stopping.get() || leaseLost.get(), () -> {
                lastActivity.set(clock.instant());
                renew.run();
            });
            lastFinished.set(clock.instant());
            lastActivity.set(clock.instant());
            heartbeat.report(report);
            return Optional.of(report);
        } finally {
            renewals.shutdownNow();
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
