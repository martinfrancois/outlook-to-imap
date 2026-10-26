package ch.fmartin.outlooktoimap.microsoft;

import com.microsoft.graph.core.requests.GraphClientFactory;
import com.microsoft.graph.core.tasks.PageIterator;
import com.microsoft.graph.models.MailFolder;
import com.microsoft.graph.models.MailFolderCollectionResponse;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.mailfolders.item.messages.delta.DeltaGetResponse;
import com.microsoft.graph.users.item.messages.item.move.MovePostRequestBody;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.RequestInformation;
import com.microsoft.kiota.RequestOption;
import com.microsoft.kiota.authentication.AccessTokenProvider;
import com.microsoft.kiota.authentication.AllowedHostsValidator;
import com.microsoft.kiota.authentication.BaseBearerTokenAuthenticationProvider;
import com.microsoft.kiota.http.middleware.options.RetryHandlerOption;
import com.microsoft.kiota.serialization.AdditionalDataHolder;
import com.microsoft.kiota.serialization.Parsable;
import com.microsoft.kiota.serialization.ParsableFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import okhttp3.OkHttpClient;

/**
 * One source account, read through Graph. The Graph SDK pages, retries throttled requests
 * (waiting for {@code Retry-After} or backing off, capped per wait) and parses replies;
 * this class picks the requests and turns Graph's error replies into the cases the copy logic
 * handles differently.
 */
public final class GraphSource {

    public static final String DEFAULT_BASE_URL = "https://graph.microsoft.com/v1.0";

    /** Graph's well-known folder names, usable as a folder id and as the first path segment. */
    static final Set<String> WELL_KNOWN_FOLDERS = Set.of(
            "archive", "clutter", "conflicts", "conversationhistory", "deleteditems", "drafts", "inbox",
            "junkemail", "localfailures", "msgfolderroot", "outbox", "recoverableitemsdeletions",
            "scheduled", "searchfolders", "sentitems", "serverfailures", "syncissues");

    // Immutable ids survive moves between folders, so one message keeps one receipt.
    private static final String IMMUTABLE_IDS = "IdType=\"ImmutableId\"";
    // Graph's largest page for messages, so a big backlog needs the fewest requests.
    private static final int PAGE_SIZE = 100;
    private static final String PREFER_PAGE_SIZE = "odata.maxpagesize=" + PAGE_SIZE;
    private static final String[] FOLDER_FIELDS = {"id", "displayName"};
    private static final String[] DELTA_FIELDS = {"id", "internetMessageId", "receivedDateTime"};
    private static final String CORRECT_THE_SOURCE = "correct or remove this source under accounts[].folders[] in the configuration";

    // Retries per request after a throttling or server error. Each waits for Retry-After, or backs
    // off exponentially, capped at RetryHandlerOption.MAX_DELAY seconds either way.
    static final int MAX_RETRIES = 5;

    private final GraphServiceClient client;

    public GraphSource(Supplier<String> accessToken) {
        this(accessToken, DEFAULT_BASE_URL, new RetryHandlerOption(null, MAX_RETRIES, RetryHandlerOption.DEFAULT_DELAY));
    }

    /** Lets tests point the client at a local server and shorten the retry delays. */
    public GraphSource(Supplier<String> accessToken, String baseUrl, RetryHandlerOption retry) {
        this.client = new GraphServiceClient(new BaseBearerTokenAuthenticationProvider(tokenProvider(accessToken, baseUrl)),
                httpClient(retry));
        this.client.getRequestAdapter().setBaseUrl(baseUrl);
    }

    /**
     * Hands out the token only for requests to the host and scheme of {@code baseUrl}. Next links
     * and stored delta links pass through here too.
     */
    static AccessTokenProvider tokenProvider(Supplier<String> accessToken, String baseUrl) {
        URI base = URI.create(baseUrl);
        return new AccessTokenProvider() {
            private final AllowedHostsValidator hosts = new AllowedHostsValidator(base.getHost());

            @Override
            public String getAuthorizationToken(URI uri, Map<String, Object> context) {
                // The validator compares hosts only, so an http:// link to Graph would pass it.
                return hosts.isUrlHostValid(uri) && base.getScheme().equalsIgnoreCase(uri.getScheme()) ? accessToken.get() : "";
            }

            @Override
            public AllowedHostsValidator getAllowedHostsValidator() {
                return hosts;
            }
        };
    }

    /**
     * The SDK's client with its retry handler, plus the immutable-id header. The SDK's overall
     * call timeout would also cover the retry handler's waits, which honour Retry-After and can add
     * up to several minutes, so it is lifted; the per-attempt connect and read timeouts stay.
     */
    static OkHttpClient httpClient(RetryHandlerOption retry) {
        return GraphClientFactory.create(new RequestOption[] {retry})
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().addHeader("Prefer", IMMUTABLE_IDS).build()))
                .callTimeout(Duration.ZERO)
                .build();
    }

    /** A message as delta reports it, without its content. */
    public record SourceMessage(String id, String internetMessageId, OffsetDateTime receivedDateTime) {}

    /** Every message delta reported since the last round, and the link that starts the next one. */
    public record Changes(List<SourceMessage> messages, String deltaLink) {}

    /**
     * Turns a configured source into a Graph folder id. A well-known name is its own id; a path such
     * as {@code Projects/2026} is looked up one display name at a time, ignoring case.
     */
    public String resolveFolder(String source) {
        String[] segments = source.split("/");
        String first = segments[0].toLowerCase(Locale.ROOT);
        String id = WELL_KNOWN_FOLDERS.contains(first) ? first : childFolder(null, segments[0], source);
        for (int i = 1; i < segments.length; i++) {
            id = childFolder(id, segments[i], source);
        }
        return id;
    }

    private String childFolder(String parentId, String displayName, String source) {
        MailFolderCollectionResponse page = call(() -> firstFolderPage(parentId), GraphSource::folderGone);
        List<String> found = new ArrayList<>();
        iterate(page, MailFolderCollectionResponse::createFromDiscriminatorValue, (MailFolder folder) -> {
            if (displayName.equalsIgnoreCase(folder.getDisplayName())) {
                found.add(folder.getId());
                return false;
            }
            return true;
        }, null, GraphSource::folderGone);
        if (found.isEmpty()) {
            throw new FolderNotFoundException(
                    "no folder named \"" + displayName + "\" on the way to " + source + "; " + CORRECT_THE_SOURCE, null);
        }
        return found.getFirst();
    }

    /** The top-level folders when {@code parentId} is null, otherwise the children of that folder. */
    private MailFolderCollectionResponse firstFolderPage(String parentId) {
        if (parentId == null) {
            return client.me().mailFolders().get(rc -> {
                rc.queryParameters.top = PAGE_SIZE;
                rc.queryParameters.select = FOLDER_FIELDS;
            });
        }
        return client.me().mailFolders().byMailFolderId(parentId).childFolders().get(rc -> {
            rc.queryParameters.top = PAGE_SIZE;
            rc.queryParameters.select = FOLDER_FIELDS;
        });
    }

    /**
     * Pages through one delta round. Without a delta link the round lists the whole folder, which
     * is how the first run picks up the backlog.
     */
    public Changes changes(String folderId, String deltaLink) {
        // Failing on a 404 from the saved link would fail the mapping every poll until someone deletes the checkpoint, see docs/adr/0026-reread-a-folder-whose-saved-delta-link-answers-404.md
        DeltaGetResponse first = call(() -> firstDeltaPage(folderId, deltaLink),
                deltaLink == null ? GraphSource::folderGone : GraphSource::deltaLinkFolderGone);
        List<SourceMessage> messages = new ArrayList<>();
        String nextDeltaLink = iterate(first, DeltaGetResponse::createFromDiscriminatorValue, (Message m) -> {
            // Delta also reports messages that left the folder. Copying never deletes, so those
            // need nothing.
            if (m.getAdditionalData() == null || !m.getAdditionalData().containsKey("@removed")) {
                messages.add(new SourceMessage(m.getId(), m.getInternetMessageId(), m.getReceivedDateTime()));
            }
            return true;
        }, request -> {
            request.headers.add("Prefer", PREFER_PAGE_SIZE);
            return request;
        }, GraphSource::folderGone);
        return new Changes(messages, nextDeltaLink);
    }

    private DeltaGetResponse firstDeltaPage(String folderId, String deltaLink) {
        var delta = client.me().mailFolders().byMailFolderId(folderId).messages().delta();
        if (deltaLink != null) {
            delta = delta.withUrl(deltaLink);
        }
        return delta.get(rc -> {
            rc.headers.add("Prefer", PREFER_PAGE_SIZE);
            if (deltaLink == null) {
                rc.queryParameters.select = DELTA_FIELDS;
            }
        });
    }

    /** The message exactly as Microsoft stores it, headers and attachments included. */
    public byte[] mime(String messageId) {
        return call(() -> {
            try (InputStream in = client.me().messages().byMessageId(messageId).content().get()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, MessageGoneException::new);
    }

    /** Moves the message to Deleted Items, where the user can still restore it. */
    public void moveToDeletedItems(String messageId) {
        MovePostRequestBody body = new MovePostRequestBody();
        body.setDestinationId("deleteditems");
        call(() -> client.me().messages().byMessageId(messageId).move().post(body), MessageGoneException::new);
    }

    private <E extends Parsable, P extends Parsable & AdditionalDataHolder> String iterate(
            P page, ParsableFactory<P> factory, Function<E, Boolean> each, UnaryOperator<RequestInformation> configure,
            Function<ApiException, GraphException> notFound) {
        return call(() -> {
            try {
                PageIterator.Builder<E, P> builder = new PageIterator.Builder<E, P>()
                        .client(client)
                        .collectionPage(page)
                        .collectionPageFactory(factory)
                        .processPageItemCallback(each);
                if (configure != null) {
                    builder.requestConfigurator(configure);
                }
                PageIterator<E, P> pages = builder.build();
                pages.iterate();
                return pages.getDeltaLink();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Graph SDK paging failed", e);
            }
        }, notFound);
    }

    /**
     * Runs one request and turns Graph's error reply into the matching exception.
     *
     * @param notFound what a 404 means for this request; Graph answers a missing message and a
     *     missing folder alike
     */
    private <T> T call(Supplier<T> request, Function<ApiException, GraphException> notFound) {
        try {
            return request.get();
        } catch (ApiException e) {
            throw translate(e, notFound);
        }
    }

    private static RuntimeException translate(ApiException e, Function<ApiException, GraphException> notFound) {
        int status = e.getResponseStatusCode();
        String code = e instanceof ODataError odata && odata.getError() != null ? odata.getError().getCode() : null;
        if (status == 410 || "SyncStateNotFound".equalsIgnoreCase(code) || "resyncRequired".equalsIgnoreCase(code)) {
            return new DeltaExpiredException("delta link expired", e);
        }
        if (status == 404 || "ErrorItemNotFound".equals(code)) {
            return notFound.apply(e);
        }
        if (status == 429 || status == 503 || status == 504) {
            return new ThrottledException(status, e);
        }
        if (status == 401 || status == 403) {
            return new GraphException("Graph refused access (" + status + (code == null ? "" : ", " + code)
                    + "); the login may lack a permission", e);
        }
        return new GraphException("Graph request failed (" + status + (code == null ? "" : ", " + code) + ")", e);
    }

    /** Graph reply that is neither expected nor a sign of throttling. */
    public static class GraphException extends RuntimeException {
        GraphException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static GraphException folderGone(ApiException e) {
        return new FolderNotFoundException(
                "Microsoft says this folder no longer exists. If it was renamed, moved or deleted, " + CORRECT_THE_SOURCE, e);
    }

    private static GraphException deltaLinkFolderGone(ApiException e) {
        return new DeltaExpiredException("delta link names a folder that no longer exists", e);
    }

    /**
     * The delta link is too old, or names a folder that is gone; the folder has to be listed from
     * the start again.
     */
    public static final class DeltaExpiredException extends GraphException {
        DeltaExpiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The message no longer exists in the source account. */
    public static final class MessageGoneException extends GraphException {
        MessageGoneException(Throwable cause) {
            super("message no longer exists", cause);
        }
    }

    /** Graph kept throttling after the SDK's retries ran out. */
    public static final class ThrottledException extends GraphException {
        ThrottledException(int status, Throwable cause) {
            super("Microsoft throttled this account or was unavailable (HTTP " + status + ") after every retry;"
                    + " trying again next poll", cause);
        }
    }

    /** A configured source names a folder that does not exist, or no longer does. */
    public static final class FolderNotFoundException extends GraphException {
        FolderNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
