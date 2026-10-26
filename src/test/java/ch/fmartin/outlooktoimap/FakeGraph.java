package ch.fmartin.outlooktoimap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import ch.fmartin.outlooktoimap.microsoft.GraphSource;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.microsoft.kiota.http.middleware.options.RetryHandlerOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Microsoft Graph played by WireMock: delta pages, MIME downloads and moves, with the failure
 * replies tests need. Every delta round of a folder ends in a delta link that the next round
 * requests.
 */
public final class FakeGraph implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public final WireMockServer server = new WireMockServer(options().dynamicPort());

    public FakeGraph() {
        server.start();
    }

    public String baseUrl() {
        return server.baseUrl() + "/v1.0";
    }

    /** A Graph client with retry delays short enough for tests. */
    public GraphSource source(Supplier<String> token) {
        return new GraphSource(token, baseUrl(), new RetryHandlerOption(null, 2, 0));
    }

    public GraphSource source() {
        return source(() -> "token");
    }

    public record Item(String id, String internetMessageId, OffsetDateTime received, boolean removed) {
        public static Item of(String id) {
            return new Item(id, "<" + id + "@example.test>", OffsetDateTime.parse("2026-10-01T08:00:00Z"), false);
        }

        public static Item removed(String id) {
            return new Item(id, null, null, true);
        }
    }

    /**
     * Stubs one delta round of {@code folder}. {@code fromDeltaToken} null means the initial
     * request without a token. Each inner list is one page; the round ends with delta token
     * {@code nextDeltaToken}.
     */
    public void deltaRound(String folder, String fromDeltaToken, String nextDeltaToken, List<List<Item>> pages) {
        String deltaPath = "/v1.0/me/mailFolders/" + folder + "/messages/delta()";
        for (int i = 0; i < pages.size(); i++) {
            String pageToken = (fromDeltaToken == null ? "initial" : fromDeltaToken) + "-page" + i;
            MappingBuilder request = get(urlPathEqualTo(deltaPath));
            if (i == 0) {
                request = fromDeltaToken == null
                        ? request.withQueryParam("$deltatoken", com.github.tomakehurst.wiremock.client.WireMock.absent())
                                .withQueryParam("$skiptoken", com.github.tomakehurst.wiremock.client.WireMock.absent())
                        : request.withQueryParam("$deltatoken", equalTo(fromDeltaToken));
            } else {
                request = request.withQueryParam("$skiptoken", equalTo(pageToken));
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("value", pages.get(i).stream().map(FakeGraph::json).toList());
            if (i + 1 < pages.size()) {
                String next = (fromDeltaToken == null ? "initial" : fromDeltaToken) + "-page" + (i + 1);
                body.put("@odata.nextLink", server.baseUrl() + deltaPath + "?$skiptoken=" + next);
            } else {
                body.put("@odata.deltaLink", deltaLink(folder, nextDeltaToken));
            }
            server.stubFor(request.willReturn(okJson(body)));
        }
    }

    public String deltaLink(String folder, String token) {
        return server.baseUrl() + "/v1.0/me/mailFolders/" + folder + "/messages/delta()?$deltatoken=" + token;
    }

    /** Makes a delta token expired, as Graph reports with 410 Gone. */
    public void expireDeltaToken(String folder, String token) {
        server.stubFor(get(urlPathEqualTo("/v1.0/me/mailFolders/" + folder + "/messages/delta()"))
                .withQueryParam("$deltatoken", equalTo(token))
                .atPriority(1)
                .willReturn(aResponse().withStatus(410).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"SyncStateNotFound\",\"message\":\"expired\"}}")));
    }

    public void mime(String id, byte[] content) {
        server.stubFor(get(urlPathEqualTo("/v1.0/me/messages/" + id + "/$value"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(content)));
    }

    public void mimeFails(String id, int status) {
        server.stubFor(get(urlPathEqualTo("/v1.0/me/messages/" + id + "/$value"))
                .atPriority(1)
                .willReturn(error(status, "ErrorInternalServerError")));
    }

    public void moveSucceeds() {
        server.stubFor(post(urlPathMatching("/v1.0/me/messages/[^/]+/move"))
                .willReturn(okJson(Map.of("id", "moved-" + UUID.randomUUID()))));
    }

    /** The first move of {@code id} fails with HTTP {@code status}, later ones succeed. */
    public void moveFailsOnce(String id, int status) {
        String scenario = "move-" + id;
        server.stubFor(post(urlPathEqualTo("/v1.0/me/messages/" + id + "/move"))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(error(status, "ErrorInternalServerError"))
                .willSetStateTo("failed once"));
        server.stubFor(post(urlPathEqualTo("/v1.0/me/messages/" + id + "/move"))
                .inScenario(scenario).whenScenarioStateIs("failed once")
                .willReturn(okJson(Map.of("id", id))));
    }

    public int moves() {
        return server.findAll(RequestPatternBuilder.newRequestPattern(
                com.github.tomakehurst.wiremock.http.RequestMethod.POST, urlPathMatching("/v1.0/me/messages/[^/]+/move"))).size();
    }

    public int moves(String id) {
        return server.findAll(RequestPatternBuilder.newRequestPattern(
                com.github.tomakehurst.wiremock.http.RequestMethod.POST, urlPathEqualTo("/v1.0/me/messages/" + id + "/move"))).size();
    }

    public static ResponseDefinitionBuilder okJson(Object body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(JSON.writeValueAsString(body));
    }

    public static ResponseDefinitionBuilder error(int status, String code) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"" + code + "\",\"message\":\"test\"}}");
    }

    private static Map<String, Object> json(Item item) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", item.id());
        if (item.removed()) {
            m.put("@removed", Map.of("reason", "deleted"));
            return m;
        }
        m.put("internetMessageId", item.internetMessageId());
        m.put("receivedDateTime", item.received().toString());
        return m;
    }

    public static List<List<Item>> pages(List<Item> items, int size) {
        List<List<Item>> pages = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            pages.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        if (pages.isEmpty()) {
            pages.add(List.of());
        }
        return pages;
    }

    @Override
    public void close() {
        server.stop();
    }
}
