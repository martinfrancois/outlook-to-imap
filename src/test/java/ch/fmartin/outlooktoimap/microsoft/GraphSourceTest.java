package ch.fmartin.outlooktoimap.microsoft;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fmartin.outlooktoimap.FakeGraph;
import ch.fmartin.outlooktoimap.FakeGraph.Item;
import ch.fmartin.outlooktoimap.TestMessages;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.Changes;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.DeltaExpiredException;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.FolderNotFoundException;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.MessageGoneException;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.SourceMessage;
import ch.fmartin.outlooktoimap.microsoft.GraphSource.ThrottledException;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GraphSourceTest {

    private final FakeGraph graph = new FakeGraph();

    @AfterEach
    void stop() {
        graph.close();
    }

    @Test
    void pagesThroughTheWholeFolderAndReturnsTheDeltaLink() {
        List<Item> items = IntStream.range(0, 7).mapToObj(i -> Item.of("m" + i)).toList();
        graph.deltaRound("junkemail", null, "t1", FakeGraph.pages(items, 3));

        Changes changes = graph.source().changes("junkemail", null);

        assertThat(changes.messages()).extracting(SourceMessage::id).containsExactly("m0", "m1", "m2", "m3", "m4", "m5", "m6");
        assertThat(changes.messages().getFirst().internetMessageId()).isEqualTo("<m0@example.test>");
        assertThat(changes.deltaLink()).isEqualTo(graph.deltaLink("junkemail", "t1"));
        graph.server.verify(3, getRequestedFor(urlPathMatching(".*/delta\\(\\)")));
    }

    @Test
    void asksForImmutableIdsAndLeavesOutMessagesThatLeftTheFolder() {
        graph.deltaRound("junkemail", "t1", "t2", List.of(List.of(Item.of("kept"), Item.removed("gone"))));

        Changes changes = graph.source().changes("junkemail", graph.deltaLink("junkemail", "t1"));

        assertThat(changes.messages()).extracting(SourceMessage::id).containsExactly("kept");
        graph.server.verify(getRequestedFor(urlPathMatching(".*/delta\\(\\)"))
                .withHeader("Prefer", containing("IdType=\"ImmutableId\"")));
    }

    @Test
    void reportsAnExpiredDeltaLink() {
        graph.expireDeltaToken("junkemail", "old");

        assertThatThrownBy(() -> graph.source().changes("junkemail", graph.deltaLink("junkemail", "old")))
                .isInstanceOf(DeltaExpiredException.class);
    }

    @Test
    void waitsForRetryAfterWhenThrottledThenSucceeds() {
        stubThrottledOnce("Retry-After", "1");
        long start = System.nanoTime();

        byte[] mime = graph.source().mime("m1");

        assertThat(mime).isEqualTo(TestMessages.mime("m1"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(900);
        graph.server.verify(2, getRequestedFor(urlPathEqualTo("/v1.0/me/messages/m1/$value")));
    }

    @Test
    void backsOffWithoutRetryAfterThenSucceeds() {
        stubThrottledOnce(null, null);

        assertThat(graph.source().mime("m1")).isEqualTo(TestMessages.mime("m1"));
        graph.server.verify(2, getRequestedFor(urlPathEqualTo("/v1.0/me/messages/m1/$value")));
    }

    @Test
    void givesUpAfterTheRetryLimitWhenThrottlingContinues() {
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/messages/m1/$value"))
                .willReturn(FakeGraph.error(429, "ApplicationThrottled").withHeader("Retry-After", "0")));

        assertThatThrownBy(() -> graph.source().mime("m1")).isInstanceOf(ThrottledException.class);
        // The first attempt plus the two retries the test client allows.
        graph.server.verify(3, getRequestedFor(urlPathEqualTo("/v1.0/me/messages/m1/$value")));
    }

    @Test
    void throttlingInTheMiddleOfPagingIsRetried() {
        List<Item> items = IntStream.range(0, 4).mapToObj(i -> Item.of("m" + i)).toList();
        graph.deltaRound("junkemail", null, "t1", FakeGraph.pages(items, 2));
        graph.server.stubFor(get(urlPathMatching(".*/delta\\(\\)")).withQueryParam("$skiptoken", equalTo("initial-page1"))
                .inScenario("page").whenScenarioStateIs(Scenario.STARTED)
                .atPriority(1)
                .willReturn(FakeGraph.error(503, "ServiceUnavailable").withHeader("Retry-After", "0"))
                .willSetStateTo("recovered"));

        Changes changes = graph.source().changes("junkemail", null);

        assertThat(changes.messages()).hasSize(4);
    }

    @Test
    void reportsAMessageThatNoLongerExists() {
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/messages/m1/$value")).willReturn(FakeGraph.error(404, "ErrorItemNotFound")));

        assertThatThrownBy(() -> graph.source().mime("m1")).isInstanceOf(MessageGoneException.class);
    }

    @Test
    void movesToDeletedItems() {
        graph.moveSucceeds();

        graph.source().moveToDeletedItems("m1");

        graph.server.verify(postRequestedFor(urlPathEqualTo("/v1.0/me/messages/m1/move"))
                .withRequestBody(containing("\"deleteditems\"")));
    }

    @Test
    void resolvesWellKnownNamesWithoutAsking() {
        assertThat(graph.source().resolveFolder("JunkEmail")).isEqualTo("junkemail");
        assertThat(graph.server.getAllServeEvents()).isEmpty();
    }

    @Test
    void resolvesAFolderPathByDisplayNameAcrossPages() {
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/mailFolders")).willReturn(FakeGraph.okJson(Map.of(
                "value", List.of(Map.of("id", "f-a", "displayName", "Archive old")),
                "@odata.nextLink", graph.server.baseUrl() + "/v1.0/me/mailFolders?$skiptoken=2"))));
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/mailFolders")).withQueryParam("$skiptoken", equalTo("2"))
                .willReturn(FakeGraph.okJson(Map.of("value", List.of(Map.of("id", "f-p", "displayName", "Projects"))))));
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/mailFolders/f-p/childFolders")).willReturn(FakeGraph.okJson(Map.of(
                "value", List.of(Map.of("id", "f-2026", "displayName", "2026"))))));
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/mailFolders/inbox/childFolders")).willReturn(FakeGraph.okJson(Map.of(
                "value", List.of(Map.of("id", "f-news", "displayName", "Newsletters"))))));

        GraphSource source = graph.source();

        assertThat(source.resolveFolder("projects/2026")).isEqualTo("f-2026");
        assertThat(source.resolveFolder("Inbox/Newsletters")).isEqualTo("f-news");
        assertThatThrownBy(() -> source.resolveFolder("Projects/2027")).isInstanceOf(FolderNotFoundException.class)
                .hasMessageContaining("2027");
    }

    @Test
    void sendsTheAccessTokenOnlyToGraph() {
        graph.mime("m1", TestMessages.mime("m1"));

        graph.source(() -> "secret-token").mime("m1");

        graph.server.verify(getRequestedFor(urlPathEqualTo("/v1.0/me/messages/m1/$value"))
                .withHeader("Authorization", equalTo("Bearer secret-token")));
    }

    private void stubThrottledOnce(String header, String value) {
        var throttled = FakeGraph.error(429, "ApplicationThrottled");
        if (header != null) {
            throttled = throttled.withHeader(header, value);
        }
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/messages/m1/$value"))
                .inScenario("throttle").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(throttled).willSetStateTo("open"));
        graph.server.stubFor(get(urlPathEqualTo("/v1.0/me/messages/m1/$value"))
                .inScenario("throttle").whenScenarioStateIs("open")
                .willReturn(aResponse().withStatus(200).withBody(TestMessages.mime("m1"))));
    }
}
