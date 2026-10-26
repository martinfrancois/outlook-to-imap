package ch.fmartin.outlooktoimap.config;

import ch.fmartin.outlooktoimap.config.Config.Account;
import ch.fmartin.outlooktoimap.config.Config.Database;
import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.FolderMapping;
import ch.fmartin.outlooktoimap.config.Config.Microsoft;
import ch.fmartin.outlooktoimap.config.Config.Monitoring;
import ch.fmartin.outlooktoimap.config.Config.Security;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Reads the YAML configuration file, applies defaults and reports every problem at once. */
public final class ConfigLoader {

    // Account names become token cache file names, so they stay within a safe character set.
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final ObjectMapper MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    /** Where state goes when the file names no stateDirectory; the container image sets it to /data. */
    public static final String STATE_DIRECTORY_VARIABLE = "OUTLOOK_TO_IMAP_STATE_DIRECTORY";

    private ConfigLoader() {}

    public static Config load(Path file) {
        return load(file, System.getenv());
    }

    static Config load(Path file, Map<String, String> environment) {
        Path absolute = file.toAbsolutePath().normalize();
        RawConfig raw;
        try {
            raw = MAPPER.readValue(Files.readString(absolute), RawConfig.class);
        } catch (IOException e) {
            throw new ConfigException(List.of("cannot read " + absolute + ": " + e.getMessage()));
        } catch (JacksonException e) {
            throw new ConfigException(List.of(absolute + ": " + e.getOriginalMessage()));
        }
        if (raw == null) {
            throw new ConfigException(List.of(absolute + " is empty"));
        }
        return new Resolver(absolute.getParent(), environment.get(STATE_DIRECTORY_VARIABLE)).resolve(raw);
    }

    private static final class Resolver {
        private final Path baseDirectory;
        private final String defaultStateDirectory;
        private final List<String> errors = new ArrayList<>();

        Resolver(Path baseDirectory, String defaultStateDirectory) {
            this.baseDirectory = baseDirectory;
            this.defaultStateDirectory = defaultStateDirectory == null || defaultStateDirectory.isBlank() ? "data" : defaultStateDirectory;
        }

        Config resolve(RawConfig raw) {
            Microsoft microsoft = microsoft(raw.microsoft());
            Path stateDirectory = path(raw.stateDirectory() == null ? defaultStateDirectory : raw.stateDirectory());
            Database database = database(raw.database(), stateDirectory);
            Duration pollInterval = pollInterval(raw.pollInterval());
            Monitoring monitoring = monitoring(raw.monitoring());
            Map<String, Destination> destinations = destinations(raw.destinations());
            List<Account> accounts = accounts(raw.accounts(), microsoft, destinations);
            if (!errors.isEmpty()) {
                throw new ConfigException(errors);
            }
            return new Config(microsoft, stateDirectory, database, pollInterval, monitoring, destinations, accounts);
        }

        private Microsoft microsoft(RawMicrosoft raw) {
            if (raw == null) {
                return new Microsoft(null, Microsoft.DEFAULT_TENANT);
            }
            return new Microsoft(blankToNull(raw.clientId()), raw.tenant() == null ? Microsoft.DEFAULT_TENANT : raw.tenant());
        }

        private Database database(RawDatabase raw, Path stateDirectory) {
            String defaultUrl = Database.SQLITE_PREFIX + stateDirectory.resolve("outlook-to-imap.db");
            if (raw == null) {
                return new Database(defaultUrl, null, null);
            }
            String url = raw.url() == null ? defaultUrl : raw.url();
            if (!url.startsWith(Database.SQLITE_PREFIX) && !url.startsWith(Database.POSTGRESQL_PREFIX)) {
                errors.add("database.url must start with " + Database.SQLITE_PREFIX + " or " + Database.POSTGRESQL_PREFIX + ", got " + url);
            }
            Path passwordFile = raw.passwordFile() == null ? null : readableFile("database.passwordFile", raw.passwordFile());
            return new Database(url, blankToNull(raw.username()), passwordFile);
        }

        private Duration pollInterval(Duration raw) {
            if (raw == null) {
                return Config.DEFAULT_POLL_INTERVAL;
            }
            if (raw.compareTo(Config.MIN_POLL_INTERVAL) < 0 || raw.compareTo(Config.MAX_POLL_INTERVAL) > 0) {
                errors.add("pollInterval must be between " + Config.MIN_POLL_INTERVAL + " and "
                        + Config.MAX_POLL_INTERVAL + ", got " + raw);
            }
            return raw;
        }

        private Monitoring monitoring(RawMonitoring raw) {
            if (raw == null) {
                return new Monitoring(Monitoring.DEFAULT_HEALTH_PORT, null);
            }
            int port = raw.healthPort() == null ? Monitoring.DEFAULT_HEALTH_PORT : raw.healthPort();
            if (port < 0 || port > 65535) {
                errors.add("monitoring.healthPort must be between 0 and 65535, got " + port);
            }
            String heartbeat = blankToNull(raw.heartbeatUrl());
            if (heartbeat != null) {
                String scheme = scheme(heartbeat);
                if (!"https".equals(scheme) && !"http".equals(scheme)) {
                    errors.add("monitoring.heartbeatUrl must be an http or https URL");
                }
            }
            return new Monitoring(port, heartbeat);
        }

        private Map<String, Destination> destinations(Map<String, RawDestination> raw) {
            Map<String, Destination> destinations = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) {
                errors.add("destinations must name at least one IMAP server");
                return destinations;
            }
            for (Map.Entry<String, RawDestination> entry : raw.entrySet()) {
                String name = entry.getKey();
                RawDestination d = entry.getValue();
                String where = "destinations." + name;
                if (d == null) {
                    errors.add(where + " is empty");
                    continue;
                }
                Security security = d.security() == null ? Security.TLS : d.security();
                int port = d.port() == null ? (security == Security.TLS ? 993 : 143) : d.port();
                if (port < 1 || port > 65535) {
                    errors.add(where + ".port must be between 1 and 65535, got " + port);
                }
                require(where + ".host", d.host());
                require(where + ".username", d.username());
                Path passwordFile = d.passwordFile() == null
                        ? missing(where + ".passwordFile")
                        : readableFile(where + ".passwordFile", d.passwordFile());
                destinations.put(name, new Destination(name, d.host(), port, security, d.username(), passwordFile));
            }
            return destinations;
        }

        private List<Account> accounts(List<RawAccount> raw, Microsoft microsoft, Map<String, Destination> destinations) {
            List<Account> accounts = new ArrayList<>();
            if (raw == null || raw.isEmpty()) {
                errors.add("accounts must list at least one Microsoft account");
                return accounts;
            }
            Set<String> names = new HashSet<>();
            for (int i = 0; i < raw.size(); i++) {
                RawAccount a = raw.get(i);
                String where = "accounts[" + i + "]";
                if (a == null) {
                    errors.add(where + " is empty");
                    continue;
                }
                if (a.name() == null || !ACCOUNT_NAME.matcher(a.name()).matches()) {
                    errors.add(where + ".name must use only letters, digits, '.', '_' and '-', got " + a.name());
                } else if (!names.add(a.name().toLowerCase(Locale.ROOT))) {
                    errors.add(where + ".name " + a.name() + " is used twice");
                }
                if (a.email() == null || !a.email().contains("@")) {
                    errors.add(where + ".email must be the Microsoft account's email address");
                }
                String clientId = a.clientId() != null ? a.clientId() : microsoft.clientId();
                if (clientId == null) {
                    errors.add(where + " has no clientId: set microsoft.clientId or the account's clientId");
                }
                String tenant = a.tenant() != null ? a.tenant() : microsoft.tenant();
                accounts.add(new Account(a.name(), a.email(), clientId, tenant, folders(where, a.folders(), destinations)));
            }
            return accounts;
        }

        private List<FolderMapping> folders(String where, List<RawFolder> raw, Map<String, Destination> destinations) {
            List<FolderMapping> folders = new ArrayList<>();
            if (raw == null || raw.isEmpty()) {
                errors.add(where + ".folders must list at least one folder to copy");
                return folders;
            }
            Set<String> sources = new HashSet<>();
            for (int i = 0; i < raw.size(); i++) {
                RawFolder f = raw.get(i);
                String at = where + ".folders[" + i + "]";
                if (f == null) {
                    errors.add(at + " is empty");
                    continue;
                }
                require(at + ".source", f.source());
                require(at + ".folder", f.folder());
                if (f.source() != null && !sources.add(f.source().toLowerCase(Locale.ROOT))) {
                    errors.add(at + ".source " + f.source() + " is listed twice for this account");
                }
                if (f.destination() == null || !destinations.containsKey(f.destination())) {
                    errors.add(at + ".destination must name an entry under destinations, got " + f.destination());
                }
                folders.add(new FolderMapping(f.source(), f.destination(), f.folder(),
                        Boolean.TRUE.equals(f.removeSourceAfterSync())));
            }
            return folders;
        }

        private void require(String where, String value) {
            if (value == null || value.isBlank()) {
                errors.add(where + " is required");
            }
        }

        private Path missing(String where) {
            errors.add(where + " is required");
            return null;
        }

        private Path readableFile(String where, String value) {
            Path p = path(value);
            if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
                errors.add(where + " " + p + " is not a readable file");
            }
            return p;
        }

        private Path path(String value) {
            return baseDirectory.resolve(value).normalize();
        }

        private static String scheme(String url) {
            try {
                return URI.create(url).getScheme();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    record RawConfig(
            RawMicrosoft microsoft,
            String stateDirectory,
            RawDatabase database,
            Duration pollInterval,
            RawMonitoring monitoring,
            Map<String, RawDestination> destinations,
            List<RawAccount> accounts) {}

    record RawMicrosoft(String clientId, String tenant) {}

    record RawDatabase(String url, String username, String passwordFile) {}

    record RawMonitoring(Integer healthPort, String heartbeatUrl) {}

    record RawDestination(String host, Integer port, Security security, String username, String passwordFile) {}

    record RawAccount(String name, String email, String clientId, String tenant, List<RawFolder> folders) {}

    record RawFolder(String source, String destination, String folder, Boolean removeSourceAfterSync) {}
}
