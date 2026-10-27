package ch.fmartin.outlooktoimap.microsoft;

import ch.fmartin.outlooktoimap.config.Config.Account;
import com.microsoft.aad.msal4j.DeviceCodeFlowParameters;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IHttpClient;
import com.microsoft.aad.msal4j.MsalInteractionRequiredException;
import com.microsoft.aad.msal4j.MsalServiceException;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Signs Microsoft accounts in with the device code flow and hands out access tokens afterwards.
 * MSAL refreshes tokens; this class decides where its cache lives and turns MSAL's failures into
 * either "log in again" or "try again later".
 */
public final class MicrosoftLogin implements AccessTokens {

    public static final String DEFAULT_AUTHORITY_HOST = "https://login.microsoftonline.com/";

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    private final Path tokenDirectory;
    private final String authorityHost;
    private final IHttpClient httpClient;
    private final Function<Path, TokenCacheFile> newCacheFile;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public MicrosoftLogin(Path stateDirectory) {
        this(stateDirectory, DEFAULT_AUTHORITY_HOST, null);
    }

    /** Lets tests point MSAL at a local server instead of Microsoft. */
    public MicrosoftLogin(Path stateDirectory, String authorityHost, IHttpClient httpClient) {
        this(stateDirectory, authorityHost, httpClient, TokenCacheFile::new);
    }

    /** Lets tests make writing the token cache fail. */
    MicrosoftLogin(Path stateDirectory, String authorityHost, IHttpClient httpClient, Function<Path, TokenCacheFile> newCacheFile) {
        this.tokenDirectory = stateDirectory.resolve("tokens");
        this.authorityHost = authorityHost;
        this.httpClient = httpClient;
        this.newCacheFile = newCacheFile;
    }

    /**
     * Runs the device code flow: {@code instructions} receives Microsoft's "go to this page and
     * enter this code" text, and the call returns once the user finished on another device.
     * Signing in to a different Microsoft account than the configured one fails, and its login is
     * removed from the token file again.
     */
    public void authorize(Account account, Consumer<String> instructions) {
        Client client = client(account);
        IAuthenticationResult result;
        try {
            result = client.app()
                    .acquireToken(DeviceCodeFlowParameters.builder(account.graphScopes(), code -> instructions.accept(code.message()))
                            .build())
                    .join();
        } catch (CompletionException e) {
            throw new MicrosoftLoginException("device code login for account " + account.name() + " failed: "
                    + e.getCause().getMessage(), e.getCause());
        }
        // Without this check an account configured for one address could silently copy another address's mail, see docs/adr/0008-device-code-sign-in-with-a-private-token-file-per-account.md
        String signedIn = result.account().username();
        if (signedIn == null || !signedIn.equalsIgnoreCase(account.email())) {
            String refused = "signed in as " + signedIn + " but account " + account.name() + " is configured for " + account.email();
            try {
                client.app().removeAccount(result.account()).join();
            } catch (CompletionException e) {
                // MSAL saved the login before the check; accessToken never uses it, but it stays on disk.
                throw new MicrosoftLoginException(refused + ", and removing that login failed: " + e.getCause().getMessage()
                        + "; delete " + client.cache().file() + " by hand", e.getCause());
            }
            throw new MicrosoftLoginException(refused + "; that login was not kept", null);
        }
    }

    /** A valid access token for Graph, refreshed through the saved login when needed. */
    @Override
    public String accessToken(Account account) {
        Client client = client(account);
        if (!client.cache().exists()) {
            throw new ReauthorizationRequiredException(account.name(), "no saved login", null);
        }
        IAccount cached = cachedAccount(client, account)
                .orElseThrow(() -> new ReauthorizationRequiredException(account.name(), "no saved login for " + account.email(), null));
        try {
            return client.app()
                    .acquireTokenSilently(SilentParameters.builder(account.graphScopes(), cached).build())
                    .join()
                    .accessToken();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MsalInteractionRequiredException) {
                String reason = account.removesSource()
                        ? "Microsoft refused the saved login or the Mail.ReadWrite permission source removal needs"
                        : "Microsoft refused the saved login";
                throw new ReauthorizationRequiredException(account.name(), reason, cause);
            }
            if (cause instanceof MsalServiceException service && "invalid_grant".equals(service.errorCode())) {
                throw new ReauthorizationRequiredException(account.name(), "Microsoft refused the saved login", cause);
            }
            throw new MicrosoftLoginException("cannot get a token for account " + account.name() + ": " + cause.getMessage(), cause);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("MSAL rejected its own authority URL", e);
        }
    }

    private Optional<IAccount> cachedAccount(Client client, Account account) {
        try {
            return client.app().getAccounts().join().stream()
                    .filter(cached -> isLoginOf(cached, account))
                    .findFirst();
        } catch (CompletionException e) {
            throw new MicrosoftLoginException("cannot read the saved login of account " + account.name(), e.getCause());
        }
    }

    private static boolean isLoginOf(IAccount cached, Account account) {
        return cached.username() != null
                && cached.username().toLowerCase(Locale.ROOT).equals(account.email().toLowerCase(Locale.ROOT));
    }

    private Client client(Account account) {
        return clients.computeIfAbsent(account.name(), name -> newClient(name, account));
    }

    private Client newClient(String name, Account account) {
        TokenCacheFile cache = newCacheFile.apply(tokenDirectory.resolve(name + ".json"));
        try {
            PublicClientApplication.Builder builder = PublicClientApplication.builder(account.clientId())
                    .authority(authorityHost + account.tenant() + "/")
                    .setTokenCacheAccessAspect(cache)
                    .connectTimeoutForDefaultHttpClient(CONNECT_TIMEOUT_MILLIS)
                    .readTimeoutForDefaultHttpClient(READ_TIMEOUT_MILLIS);
            if (httpClient != null) {
                builder.httpClient(httpClient).instanceDiscovery(false);
            }
            return new Client(builder.build(), cache);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid tenant for account " + name + ": " + account.tenant(), e);
        }
    }

    private record Client(PublicClientApplication app, TokenCacheFile cache) {}
}
