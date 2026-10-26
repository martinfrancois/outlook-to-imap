package ch.fmartin.outlooktoimap.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The validated configuration, with every default applied and every relative path resolved
 * against the directory of the configuration file. {@link ConfigLoader} is the only way to get
 * one.
 */
public record Config(
        Microsoft microsoft,
        Path stateDirectory,
        Database database,
        Duration pollInterval,
        Monitoring monitoring,
        Map<String, Destination> destinations,
        List<Account> accounts) {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofHours(1);
    public static final Duration MIN_POLL_INTERVAL = Duration.ofMinutes(1);
    // Every poll swaps the refresh token for a new one valid for 90 days, so any interval
    // below that keeps the login alive. 30 days leaves room for failed polls in between.
    public static final Duration MAX_POLL_INTERVAL = Duration.ofDays(30);

    public record Microsoft(String clientId, String tenant) {
        public static final String DEFAULT_TENANT = "consumers";
    }

    public record Database(String url, String username, Path passwordFile) {
        public static final String SQLITE_PREFIX = "jdbc:sqlite:";
        public static final String POSTGRESQL_PREFIX = "jdbc:postgresql:";

        public boolean isSqlite() {
            return url.startsWith(SQLITE_PREFIX);
        }
    }

    public record Monitoring(int healthPort, String heartbeatUrl) {
        public static final int DEFAULT_HEALTH_PORT = 8080;
    }

    public enum Security {
        /** TLS from the first byte, normally port 993. */
        TLS,
        /** Plain connection upgraded with STARTTLS before login, normally port 143. */
        STARTTLS
    }

    public record Destination(
            String name, String host, int port, Security security, String username, Path passwordFile) {}

    public record Account(String name, String email, String clientId, String tenant, List<FolderMapping> folders) {

        /** Moving a message to Deleted Items needs write access, reading needs only read access. */
        public boolean removesSource() {
            return folders.stream().anyMatch(FolderMapping::removeSourceAfterSync);
        }

        public Set<String> graphScopes() {
            return removesSource()
                    ? Set.of("https://graph.microsoft.com/Mail.ReadWrite")
                    : Set.of("https://graph.microsoft.com/Mail.Read");
        }
    }

    /** {@code destinationFolder} is the YAML key {@code folder}. */
    public record FolderMapping(String source, String destination, String destinationFolder, boolean removeSourceAfterSync) {}
}
