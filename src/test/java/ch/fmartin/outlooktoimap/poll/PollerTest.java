package ch.fmartin.outlooktoimap.poll;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.outlooktoimap.Databases;
import ch.fmartin.outlooktoimap.Databases.Engine;
import ch.fmartin.outlooktoimap.FakeGraph;
import ch.fmartin.outlooktoimap.FakeGraph.Item;
import ch.fmartin.outlooktoimap.FakeImap;
import ch.fmartin.outlooktoimap.TestMessages;
import ch.fmartin.outlooktoimap.config.Config;
import ch.fmartin.outlooktoimap.config.Config.Account;
import ch.fmartin.outlooktoimap.config.Config.Database;
import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.FolderMapping;
import ch.fmartin.outlooktoimap.config.Config.Microsoft;
import ch.fmartin.outlooktoimap.config.Config.Monitoring;
import ch.fmartin.outlooktoimap.imap.DestinationConnection;
import ch.fmartin.outlooktoimap.imap.Fingerprint;
import ch.fmartin.outlooktoimap.imap.ImapDestination.DestinationUnavailableException;
import ch.fmartin.outlooktoimap.microsoft.AccessTokens;
import ch.fmartin.outlooktoimap.microsoft.ReauthorizationRequiredException;
import ch.fmartin.outlooktoimap.store.Counts;
import ch.fmartin.outlooktoimap.store.Store;
import ch.fmartin.outlooktoimap.store.Store.State;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PollerTest {

    private static final String JUNK = "Outlook Junk";

    private final FakeGraph graph = new FakeGraph();
    private final FakeImap fastmail = new FakeImap();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @TempDir
    Path directory;

    private Database database;
    private Store store;

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable c : closeables.reversed()) {
            c.close();
        }
        if (store != null) {
            store.close();
        }
        graph.close();
        fastmail.close();
    }

    static java.util.stream.Stream<Engine> engines() {
        return Databases.engines();
    }

    @ParameterizedTest
    @MethodSource("engines")
    void copiesTheBacklogOnceAndNeverAgainEvenAfterTheCopiesAreDeleted(Engine engine) {
        open(engine);
        List<Item> backlog = IntStream.range(0, 5).mapToObj(i -> Item.of("m" + i)).toList();
        backlog.forEach(i -> graph.mime(i.id(), TestMessages.mime(i.id())));
        graph.deltaRound("junkemail", null, "t1", FakeGraph.pages(backlog, 2));
        graph.deltaRound("junkemail", "t1", "t2", List.of(List.of()));
        Config config = config(account("hotmail", false));

        PollReport first = poll(config);

        assertThat(first.succeeded()).isTrue();
        assertThat(first.accounts().getFirst().counts().imported()).isEqualTo(5);
        assertThat(fastmail.messages(JUNK)).hasSize(5);
        assertThat(new String(fastmail.messages(JUNK).getFirst(), StandardCharsets.UTF_8))
                .isEqualTo(new String(TestMessages.mime("m0"), StandardCharsets.UTF_8));
        assertThat(store.checkpoint("hotmail", "junkemail").get().deltaLink()).isEqualTo(graph.deltaLink("junkemail", "t1"));

        // The user deletes the spam in Fastmail. The next round lists nothing new, and even a full
        // reread (an expired delta link) must not bring the deleted copies back.
        fastmail.deleteAll(JUNK);
        assertThat(poll(config).accounts().getFirst().counts().imported()).isZero();
        graph.expireDeltaToken("junkemail", "t2");
        graph.deltaRound("junkemail", null, "t3", FakeGraph.pages(backlog, 2));
        PollReport reread = poll(config);

        assertThat(reread.accounts().getFirst().counts().imported()).isZero();
        assertThat(reread.accounts().getFirst().counts().skipped()).isEqualTo(5);
        assertThat(fastmail.messages(JUNK)).isEmpty();
        assertThat(graph.moves()).isZero();
    }

    @Test
    void withRemovalOffTheSourceIsNeverTouched() {
        open(Engine.SQLITE);
        stubOne("m1");

        poll(config(account("hotmail", false)));

        assertThat(graph.moves()).isZero();
        assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.IMPORTED);
    }

    @Test
    void withRemovalOnTheSourceMovesToDeletedItemsAfterTheVerifiedImport() {
        open(Engine.SQLITE);
        stubOne("m1");
        graph.moveSucceeds();

        PollReport report = poll(config(account("hotmail", true)));

        assertThat(report.accounts().getFirst().counts().removed()).isEqualTo(1);
        assertThat(fastmail.messages(JUNK)).hasSize(1);
        assertThat(graph.moves("m1")).isEqualTo(1);
        assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.REMOVED);
    }

    @Test
    void switchingRemovalOnRemovesWhatWasCopiedBeforeWithoutCopyingItAgain() {
        open(Engine.SQLITE);
        List<Item> backlog = List.of(Item.of("old1"), Item.of("old2"));
        backlog.forEach(i -> graph.mime(i.id(), TestMessages.mime(i.id())));
        graph.deltaRound("junkemail", null, "t1", List.of(backlog));
        poll(config(account("hotmail", false)));
        // One copy was deleted in Fastmail on purpose. The receipt is enough to remove the source.
        fastmail.deleteAll(JUNK);
        graph.deltaRound("junkemail", "t1", "t2", List.of(List.of()));
        graph.moveSucceeds();

        PollReport report = poll(config(account("hotmail", true)));

        assertThat(report.accounts().getFirst().counts().removed()).isEqualTo(2);
        assertThat(report.accounts().getFirst().counts().imported()).isZero();
        assertThat(fastmail.messages(JUNK)).isEmpty();
        assertThat(graph.moves("old1")).isEqualTo(1);
        assertThat(graph.moves("old2")).isEqualTo(1);
        // The full reread happened instead of the incremental round from t1.
        graph.server.verify(0, getRequestedFor(urlPathMatching(".*/delta\\(\\)")).withQueryParam("$deltatoken",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("t1")));
    }

    @Test
    void aFailedRemovalIsRetriedAfterARestartWithoutASecondCopy() {
        open(Engine.SQLITE);
        stubOne("m1");
        graph.moveFailsOnce("m1", 500);
        Config config = config(account("hotmail", true));

        PollReport first = poll(config);
        assertThat(first.succeeded()).isFalse();
        assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.IMPORTED);

        reopenStore();
        PollReport second = poll(config);

        assertThat(second.succeeded()).isTrue();
        assertThat(second.accounts().getFirst().counts().removed()).isEqualTo(1);
        assertThat(second.accounts().getFirst().counts().imported()).isZero();
        assertThat(fastmail.messages(JUNK)).hasSize(1);
        assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.REMOVED);
    }

    @Test
    void aRemovalWhoseReplyWasLostCopiesNothingAgainOnceTheMessageIsGone() {
        open(Engine.SQLITE);
        stubOne("m1");
        // Microsoft moved the message, but the reply that said so never arrived.
        graph.moveFailsOnce("m1", 500);
        Config config = config(account("hotmail", true));
        assertThat(poll(config).succeeded()).isFalse();
        graph.deltaRound("junkemail", null, "t2", List.of(List.of(Item.removed("m1"))));

        PollReport second = poll(config);

        assertThat(second.succeeded()).isTrue();
        assertThat(second.accounts().getFirst().counts()).isEqualTo(new Counts(0, 0, 0, 0));
        assertThat(fastmail.messages(JUNK)).hasSize(1);
        assertThat(graph.moves("m1")).isEqualTo(1);
        assertThat(store.checkpoint("hotmail", "junkemail").get().deltaLink()).endsWith("t2");
    }

    @Test
    void aSourceAlreadyGoneCountsAsRemoved() {
        open(Engine.SQLITE);
        stubOne("m1");
        graph.server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1.0/me/messages/m1/move"))
                .willReturn(FakeGraph.error(404, "ErrorItemNotFound")));

        PollReport report = poll(config(account("hotmail", true)));

        assertThat(report.succeeded()).isTrue();
        assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.REMOVED);
    }

    @Test
    void anUploadWhoseReplyWasLostIsFoundInsteadOfUploadedAgainAndBlocksRemovalMeanwhile() {
        open(Engine.SQLITE);
        stubOne("m1");
        graph.moveSucceeds();
        Config config = config(account("hotmail", true));

        // The server stores the message, then the connection drops before its reply arrives.
        PollReport first = poll(config, d -> new FailingAfterAppend(fastmail.connect(d)));

        assertThat(first.succeeded()).isFalse();
        assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.PENDING);
        assertThat(graph.moves()).isZero();
        assertThat(fastmail.messages(JUNK)).hasSize(1);

        reopenStore();
        PollReport second = poll(config);

        assertThat(second.succeeded()).isTrue();
        assertThat(second.accounts().getFirst().counts().imported()).isEqualTo(1);
        assertThat(fastmail.messages(JUNK)).hasSize(1);
        assertThat(graph.moves("m1")).isEqualTo(1);
    }

    @Test
    void anEarlierUploadIsFoundByTheRecordedHashEvenWhenMicrosoftServesDifferentBytesLater() {
        open(Engine.SQLITE);
        stubOne("m1");
        byte[] original = TestMessages.mime("m1");
        Config config = config(account("hotmail", false));
        poll(config, d -> new FailingAfterAppend(fastmail.connect(d)));
        // Same message, but Microsoft regenerated its MIME, so a fresh download hashes differently.
        graph.mime("m1", TestMessages.mime("<m1@example.test>", "Win a prize m1 (regenerated)", "Wed, 01 Oct 2026 08:00:00 +0000"));

        PollReport second = poll(config);

        assertThat(second.succeeded()).isTrue();
        assertThat(second.accounts().getFirst().counts().imported()).isEqualTo(1);
        assertThat(fastmail.messages(JUNK)).singleElement().isEqualTo(original);
    }

    @Test
    void aPendingUploadToADestinationNoLongerConfiguredIsCopiedToTheCurrentOne() {
        open(Engine.SQLITE);
        stubOne("m1");
        poll(config(account("hotmail", false)), d -> new FailingAfterAppend(fastmail.connect(d)));
        assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.PENDING);
        try (FakeImap dovecot = new FakeImap()) {
            Account moved = new Account("hotmail", "hotmail@hotmail.test", "app", "consumers",
                    List.of(new FolderMapping("junkemail", "dovecot", JUNK, false)));
            Config config = new Config(new Microsoft("app", "consumers"), directory, database, Duration.ofHours(1),
                    new Monitoring(0, null), Map.of("dovecot", dovecot.destination("dovecot", directory)), List.of(moved));

            PollReport report = poll(config, dovecot::connect);

            assertThat(report.succeeded()).isTrue();
            assertThat(dovecot.messages(JUNK)).hasSize(1);
            assertThat(store.receipt("hotmail", "m1").get().destination()).isEqualTo("dovecot");
        }
    }

    @Test
    void anUploadThatNeverReachedTheServerIsRetried() {
        open(Engine.SQLITE);
        stubOne("m1");
        Config config = config(account("hotmail", false));

        PollReport first = poll(config, d -> new FailingBeforeAppend(fastmail.connect(d)));
        assertThat(first.succeeded()).isFalse();
        assertThat(fastmail.messages(JUNK)).isEmpty();

        PollReport second = poll(config);

        assertThat(second.accounts().getFirst().counts().imported()).isEqualTo(1);
        assertThat(fastmail.messages(JUNK)).hasSize(1);
    }

    @Test
    void oneBadMessageDoesNotStopTheOthersAndIsRetriedNextPoll() {
        open(Engine.SQLITE);
        List<Item> items = List.of(Item.of("good1"), Item.of("bad"), Item.of("good2"));
        items.forEach(i -> graph.mime(i.id(), TestMessages.mime(i.id())));
        graph.mimeFails("bad", 500);
        graph.deltaRound("junkemail", null, "t1", List.of(items));
        Config config = config(account("hotmail", false));

        PollReport first = poll(config);

        assertThat(first.accounts().getFirst().counts().imported()).isEqualTo(2);
        assertThat(first.accounts().getFirst().counts().failed()).isEqualTo(1);
        assertThat(store.checkpoint("hotmail", "junkemail")).isEmpty();

        graph.server.resetMappings();
        items.forEach(i -> graph.mime(i.id(), TestMessages.mime(i.id())));
        graph.deltaRound("junkemail", null, "t1", List.of(items));
        PollReport second = poll(config);

        assertThat(second.accounts().getFirst().counts().imported()).isEqualTo(1);
        assertThat(second.accounts().getFirst().counts().skipped()).isEqualTo(2);
        assertThat(fastmail.messages(JUNK)).hasSize(3);
    }

    @Test
    void anAccountThatNeedsANewLoginDoesNotStopTheOtherAccount() {
        open(Engine.SQLITE);
        stubOne("m1");
        Account broken = account("live", false);
        Account working = account("hotmail", false);
        AccessTokens tokens = a -> {
            if (a.name().equals("live")) {
                throw new ReauthorizationRequiredException("live", "Microsoft refused the saved login", null);
            }
            return "token";
        };

        PollReport report = poll(config(broken, working), tokens, null);

        assertThat(report.accounts()).extracting(AccountResult::account).containsExactly("live", "hotmail");
        assertThat(report.accounts().get(0).succeeded()).isFalse();
        assertThat(report.accounts().get(0).error()).contains("outlook-to-imap authorize live");
        assertThat(report.accounts().get(1).counts().imported()).isEqualTo(1);
        assertThat(store.statuses()).extracting(Store.AccountStatus::account).containsExactly("hotmail", "live");
        assertThat(store.statuses().get(1).lastSuccessAt()).isNull();
    }

    @Test
    void aThrottledAccountWaitsForTheNextPollWhileTheOtherAccountContinues() {
        open(Engine.SQLITE);
        // Two mailboxes behind the same fake: "throttled" is the one Microsoft keeps refusing.
        graph.server.stubFor(get(urlPathMatching("/v1.0/me/mailFolders/inbox/messages/delta\\(\\)"))
                .willReturn(FakeGraph.error(429, "ApplicationThrottled").withHeader("Retry-After", "0")));
        stubOne("m1");
        Account throttled = new Account("throttled", "t@hotmail.test", "app", "consumers",
                List.of(new FolderMapping("inbox", "fastmail", "Throttled Inbox", false)));

        PollReport report = poll(config(throttled, account("hotmail", false)));

        assertThat(report.accounts().get(0).error()).contains("throttled");
        assertThat(report.accounts().get(1).counts().imported()).isEqualTo(1);
        assertThat(store.checkpoint("throttled", "inbox")).isEmpty();
    }

    @Test
    void aMessageMovedBetweenTwoSyncedFoldersIsCopiedOnce() {
        open(Engine.SQLITE);
        graph.mime("moved", TestMessages.mime("moved"));
        graph.deltaRound("inbox", null, "i1", List.of(List.of(Item.of("moved"))));
        graph.deltaRound("archive", null, "a1", List.of(List.of(Item.of("moved"))));
        Account account = new Account("hotmail", "me@hotmail.test", "app", "consumers", List.of(
                new FolderMapping("inbox", "fastmail", "Outlook Inbox", false),
                new FolderMapping("archive", "fastmail", "Outlook Archive", false)));

        PollReport report = poll(config(account));

        assertThat(report.accounts().getFirst().counts().imported()).isEqualTo(1);
        assertThat(fastmail.messages("Outlook Inbox")).hasSize(1);
        assertThat(fastmail.messages("Outlook Archive")).isEmpty();
    }

    @Test
    void theSameConfigurationWorksForTwoDifferentImapServers() {
        open(Engine.SQLITE);
        try (FakeImap other = new FakeImap()) {
            graph.mime("a", TestMessages.mime("a"));
            graph.mime("b", TestMessages.mime("b"));
            graph.deltaRound("junkemail", null, "t1", List.of(List.of(Item.of("a"))));
            graph.deltaRound("inbox", null, "i1", List.of(List.of(Item.of("b"))));
            Account account = new Account("hotmail", "me@hotmail.test", "app", "consumers", List.of(
                    new FolderMapping("junkemail", "fastmail", JUNK, false),
                    new FolderMapping("inbox", "dovecot", "From Outlook", false)));
            Map<String, Destination> destinations = new LinkedHashMap<>();
            destinations.put("fastmail", fastmail.destination("fastmail", directory));
            destinations.put("dovecot", other.destination("dovecot", directory));
            Config config = new Config(new Microsoft("app", "consumers"), directory, database, Duration.ofHours(1),
                    new Monitoring(0, null), destinations, List.of(account));

            PollReport report = new Poller(config, store, a -> "token", a -> graph.source(),
                    d -> (d.name().equals("fastmail") ? fastmail : other).connect(d), Clock.systemUTC())
                    .poll(false, line -> {}, () -> false, () -> {});

            assertThat(report.succeeded()).isTrue();
            assertThat(fastmail.messages(JUNK)).hasSize(1);
            assertThat(other.messages("From Outlook")).hasSize(1);
        }
    }

    @Test
    void aDryRunListsThePlanAndChangesNothing() {
        open(Engine.SQLITE);
        stubOne("m1");
        List<String> plan = new ArrayList<>();

        PollReport report = poller(config(account("hotmail", true)), a -> "token", null)
                .poll(true, plan::add, () -> false, () -> {});

        assertThat(report.succeeded()).isTrue();
        assertThat(plan).hasSize(2);
        assertThat(plan.get(0)).contains("copy").contains("<m1@example.test>");
        assertThat(plan.get(1)).contains("remove from the source");
        assertThat(fastmail.messages(JUNK)).isEmpty();
        assertThat(graph.moves()).isZero();
        assertThat(store.receipt("hotmail", "m1")).isEmpty();
        assertThat(store.checkpoint("hotmail", "junkemail")).isEmpty();
        assertThat(store.statuses()).isEmpty();
        graph.server.verify(0, getRequestedFor(urlPathEqualTo("/v1.0/me/messages/m1/$value")));
    }

    @Test
    void aStopRequestEndsThePollAtAMessageBoundaryAndKeepsTheCheckpoint() {
        open(Engine.SQLITE);
        List<Item> items = List.of(Item.of("m1"), Item.of("m2"), Item.of("m3"));
        items.forEach(i -> graph.mime(i.id(), TestMessages.mime(i.id())));
        graph.deltaRound("junkemail", null, "t1", List.of(items));
        AtomicInteger handled = new AtomicInteger();

        PollReport report = poller(config(account("hotmail", false)), a -> "token", null)
                .poll(false, line -> {}, () -> handled.get() >= 1, handled::incrementAndGet);

        assertThat(report.accounts().getFirst().counts().imported()).isEqualTo(1);
        assertThat(store.checkpoint("hotmail", "junkemail")).isEmpty();
        // Cut short is not a success, even with nothing failed.
        assertThat(report.interrupted()).isTrue();
        assertThat(report.succeeded()).isFalse();
        assertThat(store.statuses().getFirst().lastSuccessAt()).isNull();
    }

    @Test
    void anEarlierUploadsDestinationBeingDownFailsOnlyThatMessage() {
        open(Engine.SQLITE);
        stubOne("m1");
        poll(config(account("hotmail", false)), d -> new FailingAfterAppend(fastmail.connect(d)));
        graph.mime("m2", TestMessages.mime("m2"));
        graph.deltaRound("junkemail", null, "t2", List.of(List.of(Item.of("m1"), Item.of("m2"))));
        try (FakeImap dovecot = new FakeImap()) {
            Account moved = new Account("hotmail", "hotmail@hotmail.test", "app", "consumers",
                    List.of(new FolderMapping("junkemail", "dovecot", JUNK, false)));
            Map<String, Destination> destinations = new LinkedHashMap<>();
            destinations.put("fastmail", fastmail.destination("fastmail", directory));
            destinations.put("dovecot", dovecot.destination("dovecot", directory));
            Config config = new Config(new Microsoft("app", "consumers"), directory, database, Duration.ofHours(1),
                    new Monitoring(0, null), destinations, List.of(moved));

            PollReport report = poll(config, d -> {
                if (d.name().equals("fastmail")) {
                    throw new DestinationUnavailableException("fastmail", new java.io.IOException("connection refused"));
                }
                return dovecot.connect(d);
            });

            assertThat(report.accounts().getFirst().counts()).isEqualTo(new Counts(1, 0, 0, 1));
            assertThat(dovecot.messages(JUNK)).hasSize(1);
            assertThat(store.receipt("hotmail", "m1").get().state()).isEqualTo(State.PENDING);
        }
    }

    private void stubOne(String id) {
        graph.mime(id, TestMessages.mime(id));
        graph.deltaRound("junkemail", null, "t1", List.of(List.of(Item.of(id))));
    }

    private void open(Engine engine) {
        database = Databases.fresh(engine, directory);
        store = Store.open(database);
    }

    private void reopenStore() {
        store.close();
        store = Store.open(database);
    }

    private Account account(String name, boolean removeSource) {
        return new Account(name, name + "@hotmail.test", "app", "consumers",
                List.of(new FolderMapping("junkemail", "fastmail", JUNK, removeSource)));
    }

    private Config config(Account... accounts) {
        return new Config(new Microsoft("app", "consumers"), directory, database, Duration.ofHours(1),
                new Monitoring(0, null), Map.of("fastmail", fastmail.destination("fastmail", directory)), List.of(accounts));
    }

    private PollReport poll(Config config) {
        return poll(config, a -> "token", null);
    }

    private PollReport poll(Config config, Function<Destination, DestinationConnection> destinations) {
        return poll(config, a -> "token", destinations);
    }

    private PollReport poll(Config config, AccessTokens tokens, Function<Destination, DestinationConnection> destinations) {
        return poller(config, tokens, destinations).poll(false, line -> {}, () -> false, () -> {});
    }

    private Poller poller(Config config, AccessTokens tokens, Function<Destination, DestinationConnection> destinations) {
        return new Poller(config, store, tokens, a -> graph.source(),
                destinations != null ? destinations : fastmail::connect, Clock.systemUTC());
    }

    /** Stores the message, then reports the connection lost, as when a reply never arrives. */
    private record FailingAfterAppend(DestinationConnection delegate) implements DestinationConnection {
        @Override
        public OptionalLong append(String folder, byte[] mime, Date received) {
            delegate.append(folder, mime, received);
            throw new DestinationUnavailableException("fastmail", new java.io.IOException("connection reset"));
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

    /** Loses the connection before anything reaches the server. */
    private record FailingBeforeAppend(DestinationConnection delegate) implements DestinationConnection {
        @Override
        public OptionalLong append(String folder, byte[] mime, Date received) {
            throw new DestinationUnavailableException("fastmail", new java.io.IOException("connection refused"));
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
}
