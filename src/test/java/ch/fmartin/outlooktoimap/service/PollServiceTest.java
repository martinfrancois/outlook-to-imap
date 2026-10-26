package ch.fmartin.outlooktoimap.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.outlooktoimap.Databases;
import ch.fmartin.outlooktoimap.Databases.Engine;
import ch.fmartin.outlooktoimap.FakeGraph;
import ch.fmartin.outlooktoimap.FakeGraph.Item;
import ch.fmartin.outlooktoimap.FakeImap;
import ch.fmartin.outlooktoimap.TestMessages;
import ch.fmartin.outlooktoimap.config.Config;
import ch.fmartin.outlooktoimap.config.Config.Account;
import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.FolderMapping;
import ch.fmartin.outlooktoimap.config.Config.Microsoft;
import ch.fmartin.outlooktoimap.config.Config.Monitoring;
import ch.fmartin.outlooktoimap.imap.DestinationConnection;
import ch.fmartin.outlooktoimap.imap.Fingerprint;
import ch.fmartin.outlooktoimap.microsoft.AccessTokens;
import ch.fmartin.outlooktoimap.microsoft.ReauthorizationRequiredException;
import ch.fmartin.outlooktoimap.store.Counts;
import ch.fmartin.outlooktoimap.store.Store;
import ch.fmartin.outlooktoimap.store.Store.AccountStatus;
import ch.fmartin.outlooktoimap.poll.PollReport;
import ch.fmartin.outlooktoimap.poll.Poller;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PollServiceTest {

    private final FakeGraph graph = new FakeGraph();
    private final FakeImap fastmail = new FakeImap();
    private final WireMockServer healthchecks = new WireMockServer(options().dynamicPort());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));

    @TempDir
    Path directory;

    private Store store;
    private Config config;

    @BeforeEach
    void setUp() {
        healthchecks.start();
        healthchecks.stubFor(post(urlPathEqualTo("/ping")).willReturn(aResponse().withStatus(200)));
        healthchecks.stubFor(post(urlPathEqualTo("/ping/fail")).willReturn(aResponse().withStatus(200)));
        store = Store.open(Databases.fresh(Engine.SQLITE, directory));
        config = new Config(new Microsoft("app", "consumers"), directory, null, Duration.ofHours(1),
                new Monitoring(0, Monitoring.DEFAULT_HEALTH_BIND_ADDRESS, null),
                Map.of("fastmail", fastmail.destination("fastmail", directory)),
                List.of(new Account("hotmail", "me@hotmail.test", "app", "consumers",
                        List.of(new FolderMapping("junkemail", "fastmail", "Outlook Junk", false)))));
        graph.mime("m1", TestMessages.mime("m1"));
        graph.deltaRound("junkemail", null, "t1", List.of(List.of(Item.of("m1"))));
    }

    @AfterEach
    void tearDown() {
        store.close();
        graph.close();
        fastmail.close();
        healthchecks.stop();
    }

    @Test
    void aSuccessfulPollPingsTheHeartbeat() {
        Optional<PollReport> report = service(a -> "token").pollOnce(false, line -> {});

        assertThat(report).get().extracting(PollReport::succeeded).isEqualTo(true);
        healthchecks.verify(1, postRequestedFor(urlPathEqualTo("/ping")).withRequestBody(containing("hotmail: 1 imported")));
        healthchecks.verify(0, postRequestedFor(urlPathEqualTo("/ping/fail")));
    }

    @Test
    void aFailedAccountPingsTheFailureUrlWithTheReason() {
        AccessTokens revoked = a -> {
            throw new ReauthorizationRequiredException(a.name(), "Microsoft refused the saved login", null);
        };

        service(revoked).pollOnce(false, line -> {});

        healthchecks.verify(1, postRequestedFor(urlPathEqualTo("/ping/fail"))
                .withRequestBody(containing("outlook-to-imap authorize hotmail")));
    }

    @Test
    void doesNotPollWhileAnotherProcessHoldsTheLease() {
        assertThat(store.acquireLease("other-host/1", clock.instant(), clock.instant().plusSeconds(900))).isTrue();

        assertThat(service(a -> "token").pollOnce(false, line -> {})).isEmpty();
        assertThat(fastmail.messages("Outlook Junk")).isEmpty();

        // The other process died and its lease ran out.
        clock.advance(Duration.ofMinutes(16));
        assertThat(service(a -> "token").pollOnce(false, line -> {})).isPresent();
        assertThat(fastmail.messages("Outlook Junk")).hasSize(1);
    }

    @Test
    void aPollThatLosesTheLeaseStopsBeforeTheNextMessage() {
        graph.mime("m2", TestMessages.mime("m2"));
        graph.deltaRound("junkemail", null, "t1", List.of(List.of(Item.of("m1"), Item.of("m2"))));
        // While the first message uploads, this process hangs past its lease and another takes over.
        Function<Destination, DestinationConnection> stalling = d -> new AfterAppend(fastmail.connect(d), () -> {
            clock.advance(Duration.ofMinutes(16));
            assertThat(store.acquireLease("other-host/1", clock.instant(), clock.instant().plusSeconds(900))).isTrue();
        });

        Optional<PollReport> report = service(a -> "token", stalling).pollOnce(false, line -> {});

        assertThat(report).isPresent();
        assertThat(fastmail.messages("Outlook Junk")).hasSize(1);
        assertThat(store.receipt("hotmail", "m2")).isEmpty();
        assertThat(store.acquireLease("third-host/1", clock.instant(), clock.instant().plusSeconds(900))).isFalse();
        // A cut-short poll is neither a success nor a failure for the monitoring.
        assertThat(report.get().interrupted()).isTrue();
        healthchecks.verify(0, postRequestedFor(urlPathMatching("/ping.*")));
    }

    @Test
    void aDryRunIgnoresTheLeaseBecauseItCopiesAndRemovesNothing() {
        store.acquireLease("other-host/1", clock.instant(), clock.instant().plusSeconds(900));

        assertThat(service(a -> "token").pollOnce(true, line -> {})).isPresent();
        healthchecks.verify(0, postRequestedFor(urlPathEqualTo("/ping")));
    }

    @Test
    void healthFollowsPollProgressAndTheEndpointShowsEachAccount() throws Exception {
        PollService service = service(a -> "token");
        assertThat(service.healthy()).isFalse();

        service.start();
        try (HealthServer health = new HealthServer(config.monitoring().healthAddress(), service, store)) {
            waitFor(() -> service.lastFinished() != null);
            HttpResponse<String> response = get(health);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"up\"").contains("\"account\":\"hotmail\"").contains("\"imported\":1")
                    .contains("\"lastPollFinishedAt\":\"2026-10-26T10:00:00Z\"");

            // Two intervals plus the margin without progress means the poll loop is stuck.
            clock.advance(config.pollInterval().multipliedBy(2).plus(PollService.HEALTH_MARGIN).plusSeconds(1));
            assertThat(service.healthy()).isFalse();
            assertThat(get(health).statusCode()).isEqualTo(503);
        } finally {
            service.stop(Duration.ofSeconds(10));
        }
        assertThat(service.healthy()).isFalse();
    }

    @Test
    void aServiceThatFindsTheLeaseTakenTriesAgainBeforeTheNextInterval() throws Exception {
        // A crashed process left its lease behind.
        store.acquireLease("crashed-host/1", clock.instant(), clock.instant().plusSeconds(900));
        Poller poller = new Poller(config, store, a -> "token", a -> graph.source(), fastmail::connect, clock);
        PollService service = new PollService(poller, store, new Heartbeat(null), config.pollInterval(), clock,
                Duration.ofMillis(50));

        service.start();
        try {
            clock.advance(Duration.ofMinutes(16));
            waitFor(() -> service.lastFinished() != null);
            assertThat(fastmail.messages("Outlook Junk")).hasSize(1);
        } finally {
            service.stop(Duration.ofSeconds(10));
        }
    }

    @Test
    void aStopDuringASinglePollReleasesTheLeaseWhenTheGraceRunsOut() throws Exception {
        CountDownLatch uploading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PollService service = service(a -> "token", d -> new AfterAppend(fastmail.connect(d), () -> {
            uploading.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Thread once = new Thread(() -> service.pollOnce(false, line -> {}));
        once.start();
        try {
            assertThat(uploading.await(30, TimeUnit.SECONDS)).isTrue();

            service.stop(Duration.ofMillis(200));

            assertThat(store.acquireLease("next-run/1", clock.instant(), clock.instant().plusSeconds(900))).isTrue();
        } finally {
            release.countDown();
            once.join();
        }
    }

    @Test
    void aStopThatOutlastsItsGraceReleasesTheLeaseForTheNextStart() throws Exception {
        CountDownLatch uploading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // An upload that hangs, as a throttled Graph call waiting out a long Retry-After would.
        PollService service = service(a -> "token", d -> new AfterAppend(fastmail.connect(d), () -> {
            uploading.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        service.start();
        try {
            assertThat(uploading.await(30, TimeUnit.SECONDS)).isTrue();

            service.stop(Duration.ofMillis(200));

            assertThat(store.acquireLease("restarted-host/1", clock.instant(), clock.instant().plusSeconds(900))).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void theHealthEndpointAnswers503WithTheReasonWhenTheStatusCannotBeRead() throws Exception {
        Store closed = Store.open(Databases.fresh(Engine.SQLITE, directory.resolve("closed")));
        closed.close();

        try (HealthServer health = new HealthServer(config.monitoring().healthAddress(), service(a -> "token"), closed)) {
            HttpResponse<String> response = get(health);

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("\"status\":\"down\"").contains("\"error\":");
        }
    }

    @Test
    void aHalfSentRequestDoesNotKeepTheHealthEndpointFromAnsweringOthers() throws Exception {
        try (HealthServer health = new HealthServer(config.monitoring().healthAddress(), service(a -> "token"), store);
                Socket slow = new Socket("127.0.0.1", health.port())) {
            slow.getOutputStream().write("GET /hea".getBytes(StandardCharsets.US_ASCII));
            slow.getOutputStream().flush();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + health.port() + "/health"))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(503);
        }
    }

    @Test
    void theHealthEndpointClosesAConnectionThatStallsMidRequest() throws Exception {
        Duration limit = Duration.ofSeconds(HealthServer.CLIENT_TIMEOUT_SECONDS + 5);
        try (HealthServer health = new HealthServer(config.monitoring().healthAddress(), service(a -> "token"), store);
                Socket slow = new Socket("127.0.0.1", health.port())) {
            slow.getOutputStream().write("GET /hea".getBytes(StandardCharsets.US_ASCII));
            slow.getOutputStream().flush();
            slow.setSoTimeout((int) limit.toMillis());
            long start = System.nanoTime();

            // -1 means the server closed the connection; a read timeout would fail the test instead.
            assertThat(slow.getInputStream().read()).isEqualTo(-1);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(limit);
        }
    }

    @Test
    void theHealthEndpointClosesAConnectionThatStopsReadingTheReply() throws Exception {
        // JSON writes each control character as six bytes, so the reply is about 12 MB: more than
        // the socket buffers of both ends hold, and the server blocks while it writes.
        String error = "\u0001".repeat(2000);
        for (int i = 0; i < 1000; i++) {
            store.saveStatus(new AccountStatus("account-" + i, clock.instant(), null, new Counts(0, 0, 0, 1), error));
        }
        try (HealthServer health = new HealthServer(config.monitoring().healthAddress(), service(a -> "token"), store);
                Socket slow = new Socket()) {
            slow.setReceiveBufferSize(4096);
            slow.connect(new InetSocketAddress("127.0.0.1", health.port()));
            slow.getOutputStream().write("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            slow.getOutputStream().flush();
            Thread.sleep(Duration.ofSeconds(HealthServer.CLIENT_TIMEOUT_SECONDS + 3));

            // A server that kept the connection would finish the reply and then wait for the next
            // request, so the read would time out instead of reaching the end of the stream.
            slow.setSoTimeout(5_000);
            String reply = new String(slow.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);

            int bodyStart = reply.indexOf("\r\n\r\n") + 4;
            Matcher length = Pattern.compile("(?im)^content-length: *(\\d+)").matcher(reply.substring(0, bodyStart));
            assertThat(length.find()).isTrue();
            assertThat(reply.length() - bodyStart).isLessThan(Integer.parseInt(length.group(1)));
        }
    }

    private PollService service(AccessTokens tokens) {
        return service(tokens, fastmail::connect);
    }

    private PollService service(AccessTokens tokens, Function<Destination, DestinationConnection> destinations) {
        Poller poller = new Poller(config, store, tokens, a -> graph.source(), destinations, clock);
        return new PollService(poller, store, new Heartbeat(healthchecks.baseUrl() + "/ping"), config.pollInterval(), clock);
    }

    private static HttpResponse<String> get(HealthServer health) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + health.port() + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 30 seconds");
            }
            Thread.sleep(20);
        }
    }

    /** Runs {@code then} after each upload. */
    private record AfterAppend(DestinationConnection delegate, Runnable then) implements DestinationConnection {
        @Override
        public OptionalLong append(String folder, byte[] mime, Date received) {
            OptionalLong uid = delegate.append(folder, mime, received);
            then.run();
            return uid;
        }

        @Override
        public boolean contains(String folder, long uid) {
            return delegate.contains(folder, uid);
        }

        @Override
        public OptionalLong find(String folder, Fingerprint fingerprint) {
            return delegate.find(folder, fingerprint);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
