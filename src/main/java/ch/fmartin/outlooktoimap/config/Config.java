package ch.fmartin.outlooktoimap.config;

import java.net.InetSocketAddress;
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
    // A longer interval could let the 90-day refresh token run out between polls, see docs/adr/0008-device-code-sign-in-with-a-private-token-file-per-account.md
    // Every poll swaps the refresh token for a new one; 30 days leaves room for failed polls in between.
    public static final Duration MAX_POLL_INTERVAL = Duration.ofDays(30);

    public record Microsoft(String clientId, String tenant) {
        // Personal accounts are the tested case; another default would make untested work and school sign-in the usual path, see docs/adr/0009-work-and-school-accounts-best-effort.md
        public static final String DEFAULT_TENANT = "consumers";
    }

    public record Database(String url, String username, Path passwordFile) {
        public static final String SQLITE_PREFIX = "jdbc:sqlite:";
        public static final String POSTGRESQL_PREFIX = "jdbc:postgresql:";

        public boolean isSqlite() {
            return url.startsWith(SQLITE_PREFIX);
        }
    }

    public record Monitoring(int healthPort, String healthBindAddress, String heartbeatUrl) {
        public static final int DEFAULT_HEALTH_PORT = 8080;
        // A wider default would show every account's status to anyone who reaches the port, since /health has no authentication, see docs/adr/0020-verified-postgresql-tls-and-loopback-health-by-default.md
        public static final String DEFAULT_HEALTH_BIND_ADDRESS = "127.0.0.1";

        /** The address /health listens on, with a host name looked up. */
        public InetSocketAddress healthAddress() {
            InetSocketAddress address = new InetSocketAddress(healthBindAddress, healthPort);
            if (address.isUnresolved()) {
                throw new ConfigException(List.of(
                        "monitoring.healthBindAddress \"" + healthBindAddress + "\" does not resolve to an IP address"));
            }
            return address;
        }
    }

    // A plain-text value would send the destination's password unencrypted, see docs/adr/0017-imap-destinations-with-a-password-over-tls.md
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

        // Always asking for Mail.ReadWrite would let anyone holding the token file change mail that is only copied, see docs/adr/0008-device-code-sign-in-with-a-private-token-file-per-account.md
        public Set<String> graphScopes() {
            return removesSource()
                    ? Set.of("https://graph.microsoft.com/Mail.ReadWrite")
                    : Set.of("https://graph.microsoft.com/Mail.Read");
        }
    }

    /** {@code destinationFolder} is the YAML key {@code folder}. */
    public record FolderMapping(String source, String destination, String destinationFolder, boolean removeSourceAfterSync) {}
}
