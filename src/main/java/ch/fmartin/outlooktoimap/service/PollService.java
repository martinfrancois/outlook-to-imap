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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls right away and then once per interval, one poll at a time. A database lease keeps a
 * second process that shares the database, such as a manual {@code once}, from polling at the
 * same time. Stopping lets the current message finish, then ends the poll.
 */
public final class PollService {

    private static final Logger LOG = LoggerFactory.getLogger(PollService.class);
    // Held only during a poll and renewed on a timer meanwhile, so it runs out only when the holder
    // died. A hung poll keeps it; /health reports that case, because only messages count as
    // progress there.
    private static final Duration LEASE = Duration.ofMinutes(15);
    private static final Duration LEASE_RENEWAL = LEASE.dividedBy(5);
    // How long past two intervals /health waits for progress, to cover one slow message.
    static final Duration HEALTH_MARGIN = Duration.ofMinutes(15);

    private final Poller poller;
    private final Duration leaseRenewal;
    private final Store store;
    private final Heartbeat heartbeat;
    private final Duration interval;
    private final Clock clock;
    private final String holder;
    private final AtomicBoolean stopping = new AtomicBoolean();
    // Held while a poll runs, so a stop can wait for it.
    private final ReentrantLock polling = new ReentrantLock();
    private final AtomicReference<Instant> lastFinished = new AtomicReference<>();
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>();
    // Read by the health endpoint's thread, which may start before start() assigns it.
    private volatile ScheduledExecutorService executor;

    public PollService(Poller poller, Store store, Heartbeat heartbeat, Duration interval, Clock clock) {
        this(poller, store, heartbeat, interval, clock, LEASE_RENEWAL);
    }

    /** {@code leaseRenewal} lets tests see a renewal or a busy retry without waiting minutes. */
    PollService(Poller poller, Store store, Heartbeat heartbeat, Duration interval, Clock clock, Duration leaseRenewal) {
        this.leaseRenewal = leaseRenewal;
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
        polling.lock();
        try {
            return stopping.get() ? Optional.empty() : leasedPoll(dryRun, plan);
        } finally {
            polling.unlock();
        }
    }

    private Optional<PollReport> leasedPoll(boolean dryRun, Consumer<String> plan) {
        if (dryRun) {
            return Optional.of(poller.poll(true, plan, stopping::get, () -> {}));
        }
        if (!store.acquireLease(holder, clock.instant(), clock.instant().plus(LEASE))) {
            LOG.warn("another process is polling with the same database; skipping this poll");
            return Optional.empty();
        }
        AtomicBoolean leaseLost = new AtomicBoolean();
        // A database hiccup only costs one renewal; the lease outlasts several missed ones.
        Runnable renew = () -> {
            try {
                if (!leaseLost.get() && !store.renewLease(holder, clock.instant().plus(LEASE))) {
                    leaseLost.set(true);
                    LOG.error("this process no longer holds the poll lease; stopping this poll at the next message");
                }
            } catch (RuntimeException e) {
                LOG.warn("renewing the poll lease failed", e);
            }
        };
        // The timer covers long stretches without messages, such as listing a big folder while
        // Microsoft throttles; renewing after each message covers the rest.
        ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "poll-lease");
            t.setDaemon(true);
            return t;
        });
        renewals.scheduleAtFixedRate(renew, leaseRenewal.toMillis(), leaseRenewal.toMillis(), TimeUnit.MILLISECONDS);
        try {
            // Renewing after each message as well notices a lost lease before the next upload,
            // not up to one timer period later.
            PollReport report = poller.poll(false, plan, () -> stopping.get() || leaseLost.get(), () -> {
                lastActivity.set(clock.instant());
                renew.run();
            });
            lastFinished.set(clock.instant());
            lastActivity.set(clock.instant());
            if (report.interrupted()) {
                // Neither a success nor a failure: the next poll finishes the work.
                LOG.info("poll interrupted before every account was done; sending no heartbeat");
            } else {
                heartbeat.report(report);
            }
            return Optional.of(report);
        } finally {
            renewals.shutdownNow();
            awaitRenewals(renewals);
            store.releaseLease(holder);
        }
    }

    public void start() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "poll");
            t.setDaemon(false);
            return t;
        });
        // A stop drops the next scheduled poll instead of running it.
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor = scheduler;
        executor.execute(this::pollAndScheduleNext);
        LOG.info("polling every {}", interval);
    }

    /**
     * The next poll counts from the end of this one, so polls never overlap. When another process
     * held the lease, the next try comes after one renewal period instead of a whole interval, so
     * a lease left behind by a crashed process delays polling only until it expires.
     */
    private void pollAndScheduleNext() {
        Duration next = interval;
        try {
            Optional<PollReport> report = pollOnce(false, line -> {});
            if (report.isEmpty()) {
                next = leaseRenewal;
            } else {
                LOG.info("poll finished\n{}", report.get().summary());
            }
        } catch (RuntimeException e) {
            LOG.error("poll failed", e);
        }
        if (!stopping.get()) {
            try {
                executor.schedule(this::pollAndScheduleNext, next.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                LOG.debug("not scheduling another poll while stopping");
            }
        }
    }

    /**
     * Asks the current poll, from {@link #start} or {@link #pollOnce}, to stop at the next message
     * boundary and waits up to {@code grace} for it.
     */
    public void stop(Duration grace) {
        stopping.set(true);
        if (executor != null) {
            executor.shutdown();
        }
        try {
            if (polling.tryLock(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                polling.unlock();
                return;
            }
            LOG.warn("poll did not stop within {}; exiting anyway, the next start reconciles it", grace);
            if (executor != null) {
                executor.shutdownNow();
            }
            // The poll may never reach its own release before the JVM exits, and a restarted
            // process would then find the lease taken until it expires.
            store.releaseLease(holder);
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
        Duration allowed = interval.multipliedBy(2).plus(HEALTH_MARGIN);
        return Duration.between(lastActivity.get(), clock.instant()).compareTo(allowed) < 0;
    }

    public Instant lastFinished() {
        return lastFinished.get();
    }

    /** A renewal that is still running could otherwise take the lease back after the release. */
    private void awaitRenewals(ScheduledExecutorService renewals) {
        try {
            if (!renewals.awaitTermination(leaseRenewal.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("a poll lease renewal is still running; the lease may stay taken until it expires");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown-host";
        }
    }
}
