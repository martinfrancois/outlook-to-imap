package ch.fmartin.outlooktoimap.microsoft;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fmartin.outlooktoimap.config.Config.Account;
import ch.fmartin.outlooktoimap.config.Config.FolderMapping;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.microsoft.aad.msal4j.HttpRequest;
import com.microsoft.aad.msal4j.HttpResponse;
import com.microsoft.aad.msal4j.IHttpClient;
import com.microsoft.aad.msal4j.IHttpResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MicrosoftLoginTest {

    // MSAL remembers invalid_grant replies per client and account for a while, process-wide, so
    // each test uses its own client id to keep one test's refusals out of the next.
    private final String clientId = java.util.UUID.randomUUID().toString();
    private static final String FAKE_AUTHORITY_HOST = "https://login.test/";
    private static final String TOKEN = "/consumers/oauth2/v2.0/token";

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    @TempDir
    Path state;

    MicrosoftLoginTest() {
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void deviceCodeLoginSavesAPrivateTokenFileAndAsksOnlyForReadAccess() throws Exception {
        stubDeviceCode();
        stubToken("device_code", "me@hotmail.test", "access-1", "refresh-1");
        List<String> instructions = new ArrayList<>();

        login().authorize(account(false), instructions::add);

        assertThat(instructions).containsExactly("To sign in, use a web browser to open https://microsoft.test/devicelogin and enter the code ABCD-EFGH.");
        Path tokens = state.resolve("tokens/hotmail.json");
        assertThat(Files.readString(tokens)).contains("refresh-1");
        assertThat(Files.getPosixFilePermissions(tokens)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(tokens.getParent())).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        server.verify(postRequestedFor(urlPathEqualTo("/consumers/oauth2/v2.0/devicecode"))
                .withRequestBody(containing("Mail.Read"))
                .withRequestBody(containing("offline_access"))
                .withRequestBody(notContaining("Mail.ReadWrite"))
                .withRequestBody(notContaining("Mail.Send")));
    }

    @Test
    void sourceRemovalAsksForWriteAccessButNeverForSending() {
        stubDeviceCode();
        stubToken("device_code", "me@hotmail.test", "access-1", "refresh-1");

        login().authorize(account(true), line -> {});

        server.verify(postRequestedFor(urlPathEqualTo("/consumers/oauth2/v2.0/devicecode"))
                .withRequestBody(containing("Mail.ReadWrite"))
                .withRequestBody(notContaining("Mail.Send")));
    }

    @Test
    void signingInWithAnotherAccountKeepsNothing() throws Exception {
        stubDeviceCode();
        stubToken("device_code", "someone.else@hotmail.test", "access-1", "refresh-1");

        assertThatThrownBy(() -> login().authorize(account(false), line -> {}))
                .isInstanceOf(MicrosoftLoginException.class)
                .hasMessageContaining("someone.else@hotmail.test");
        assertThat(Files.readString(state.resolve("tokens/hotmail.json"))).doesNotContain("refresh-1");
        assertThatThrownBy(() -> login().accessToken(account(false))).isInstanceOf(ReauthorizationRequiredException.class);
    }

    @Test
    void refreshesUnattendedAndKeepsTheRotatedRefreshTokenAcrossRestarts() throws Exception {
        stubDeviceCode();
        // Access tokens that expire within MSAL's five-minute margin force a refresh on every call.
        stubToken("device_code", "me@hotmail.test", "access-1", "refresh-1");
        stubRefresh("refresh-1", "access-2", "refresh-2");
        stubRefresh("refresh-2", "access-3", "refresh-3");

        login().authorize(account(false), line -> {});
        assertThat(login().accessToken(account(false))).isEqualTo("access-2");
        assertThat(Files.readString(state.resolve("tokens/hotmail.json"))).contains("refresh-2");

        // A new instance reads the file again, as after a container restart.
        assertThat(login().accessToken(account(false))).isEqualTo("access-3");
        assertThat(Files.readString(state.resolve("tokens/hotmail.json"))).contains("refresh-3");
        assertThat(Files.list(state.resolve("tokens")).map(p -> p.getFileName().toString())).containsExactly("hotmail.json");
    }

    @Test
    void aRevokedLoginAsksForReauthorization() {
        stubDeviceCode();
        stubToken("device_code", "me@hotmail.test", "access-1", "refresh-1");
        stubRefreshError("refresh-1", "invalid_grant", "AADSTS70000: The provided grant has expired due to it being revoked.", null);

        login().authorize(account(false), line -> {});

        assertThatThrownBy(() -> login().accessToken(account(false)))
                .isInstanceOf(ReauthorizationRequiredException.class)
                .hasMessageContaining("outlook-to-imap authorize hotmail");
    }

    @Test
    void switchingRemovalOnWithoutANewLoginAsksForReauthorization() {
        stubDeviceCode();
        stubToken("device_code", "me@hotmail.test", "access-1", "refresh-1");
        // Microsoft refuses to widen a Mail.Read grant to Mail.ReadWrite without new consent.
        stubRefreshError("refresh-1", "invalid_grant",
                "AADSTS65001: The user or administrator has not consented to use the application.", "consent_required");

        login().authorize(account(false), line -> {});

        assertThatThrownBy(() -> login().accessToken(account(true)))
                .isInstanceOf(ReauthorizationRequiredException.class)
                .hasMessageContaining("Mail.ReadWrite");
    }

    @Test
    void noSavedLoginAsksForAuthorization() {
        assertThatThrownBy(() -> login().accessToken(account(false)))
                .isInstanceOf(ReauthorizationRequiredException.class)
                .hasMessageContaining("no saved login");
    }

    @Test
    void anUnreachableLoginServiceIsNotAReauthorization() {
        stubDeviceCode();
        stubToken("device_code", "me@hotmail.test", "access-1", "refresh-1");
        login().authorize(account(false), line -> {});
        server.stubFor(post(urlPathEqualTo(TOKEN)).withRequestBody(containing("grant_type=refresh_token"))
                .atPriority(1)
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> login().accessToken(account(false))).isInstanceOf(MicrosoftLoginException.class);
    }

    private MicrosoftLogin login() {
        return new MicrosoftLogin(state, FAKE_AUTHORITY_HOST, new LocalHttpClient(server.baseUrl()));
    }

    private Account account(boolean removeSource) {
        return new Account("hotmail", "me@hotmail.test", clientId, "consumers",
                List.of(new FolderMapping("junkemail", "fastmail", "Outlook Junk", removeSource)));
    }

    private void stubDeviceCode() {
        server.stubFor(post(urlPathEqualTo("/consumers/oauth2/v2.0/devicecode")).willReturn(json(200, Map.of(
                "device_code", "device-123",
                "user_code", "ABCD-EFGH",
                "verification_uri", "https://microsoft.test/devicelogin",
                "expires_in", 900,
                "interval", 1,
                "message", "To sign in, use a web browser to open https://microsoft.test/devicelogin and enter the code ABCD-EFGH."))));
    }

    private void stubToken(String grant, String username, String access, String refresh) {
        server.stubFor(post(urlPathEqualTo(TOKEN)).withRequestBody(containing(grant))
                .willReturn(json(200, tokenResponse(username, access, refresh))));
    }

    private void stubRefresh(String refreshToken, String access, String nextRefresh) {
        server.stubFor(post(urlPathEqualTo(TOKEN)).withRequestBody(containing("refresh_token=" + refreshToken))
                .willReturn(json(200, tokenResponse("me@hotmail.test", access, nextRefresh))));
    }

    private void stubRefreshError(String refreshToken, String error, String description, String suberror) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("error", error, "error_description", description,
                "error_codes", List.of(Integer.parseInt(description.substring(6, description.indexOf(':'))))));
        if (suberror != null) {
            body.put("suberror", suberror);
        }
        server.stubFor(post(urlPathEqualTo(TOKEN)).withRequestBody(containing("refresh_token=" + refreshToken))
                .willReturn(json(400, body)));
    }

    private Map<String, Object> tokenResponse(String username, String access, String refresh) {
        String tenant = "9188040d-6c67-4c5b-b112-36a304b66dad";
        long now = Instant.now().getEpochSecond();
        String idToken = base64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + base64("{\"aud\":\"" + clientId + "\","
                + "\"iss\":\"https://login.microsoftonline.com/" + tenant + "/v2.0\",\"iat\":" + now + ",\"nbf\":" + now
                + ",\"exp\":" + (now + 3600) + ",\"name\":\"Me\",\"oid\":\"00000000-0000-0000-1111-222222222222\","
                + "\"preferred_username\":\"" + username + "\",\"sub\":\"sub-1\",\"tid\":\"" + tenant + "\",\"ver\":\"2.0\"}") + ".";
        return Map.of(
                "token_type", "Bearer",
                "scope", "https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/Mail.ReadWrite",
                "expires_in", 60,
                "ext_expires_in", 60,
                "access_token", access,
                "refresh_token", refresh,
                "id_token", idToken,
                "client_info", base64("{\"uid\":\"00000000-0000-0000-1111-222222222222\",\"utid\":\"" + tenant + "\"}"));
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, Map<String, Object> body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body));
    }

    /** Sends MSAL's https://login.test requests to the local WireMock server instead. */
    private record LocalHttpClient(String baseUrl) implements IHttpClient {
        private static final HttpClient HTTP = HttpClient.newHttpClient();

        @Override
        public IHttpResponse send(HttpRequest request) throws Exception {
            URI target = URI.create(request.url().toString().replace(FAKE_AUTHORITY_HOST, baseUrl + "/"));
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(target);
            if (request.headers() != null) {
                request.headers().forEach((k, v) -> {
                    if (!Set.of("host", "content-length", "connection").contains(k.toLowerCase())) {
                        builder.header(k, v);
                    }
                });
            }
            builder.method(request.httpMethod().name(), request.body() == null
                    ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(request.body()));
            java.net.http.HttpResponse<String> response = HTTP.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            HttpResponse result = new HttpResponse();
            result.statusCode(response.statusCode());
            result.addHeaders(response.headers().map());
            result.body(response.body());
            return result;
        }
    }
}
