package ch.fmartin.outlooktoimap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fmartin.outlooktoimap.config.Config.Security;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path directory;

    @BeforeEach
    void passwordFile() throws IOException {
        Files.writeString(directory.resolve("fastmail-password"), "secret\n");
    }

    @Test
    void appliesDefaults() throws IOException {
        Config config = load("""
                microsoft:
                  clientId: 11111111-2222-3333-4444-555555555555
                destinations:
                  fastmail:
                    host: imap.fastmail.com
                    username: me@fastmail.test
                    passwordFile: fastmail-password
                accounts:
                  - name: hotmail
                    email: me@hotmail.test
                    folders:
                      - source: junkemail
                        destination: fastmail
                        folder: Outlook Junk
                """);

        assertThat(config.pollInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(config.microsoft().tenant()).isEqualTo("consumers");
        assertThat(config.stateDirectory()).isEqualTo(directory.resolve("data"));
        assertThat(config.database().url()).isEqualTo("jdbc:sqlite:" + directory.resolve("data/outlook-to-imap.db"));
        assertThat(config.monitoring().healthPort()).isEqualTo(8080);
        assertThat(config.monitoring().healthBindAddress()).isEqualTo("127.0.0.1");
        assertThat(config.monitoring().heartbeatUrl()).isNull();
        Config.Destination fastmail = config.destinations().get("fastmail");
        assertThat(fastmail.security()).isEqualTo(Security.TLS);
        assertThat(fastmail.port()).isEqualTo(993);
        assertThat(fastmail.passwordFile()).isEqualTo(directory.resolve("fastmail-password"));
        Config.Account account = config.accounts().getFirst();
        assertThat(account.clientId()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(account.tenant()).isEqualTo("consumers");
        assertThat(account.folders().getFirst().removeSourceAfterSync()).isFalse();
        assertThat(account.graphScopes()).containsExactly("https://graph.microsoft.com/Mail.Read");
    }

    @Test
    void readsEverySetting() throws IOException {
        Files.writeString(directory.resolve("db-password"), "pg\n");
        Config config = load("""
                microsoft:
                  clientId: shared-app
                  tenant: organizations
                stateDirectory: /var/lib/outlook-to-imap
                database:
                  url: jdbc:postgresql://db.internal/outlook
                  username: outlook
                  passwordFile: db-password
                pollInterval: PT15M
                monitoring:
                  healthPort: 0
                  healthBindAddress: 0.0.0.0
                  heartbeatUrl: https://hc-ping.com/uuid
                destinations:
                  fastmail:
                    host: imap.fastmail.com
                    username: me@fastmail.test
                    passwordFile: fastmail-password
                  dovecot:
                    host: mail.example.test
                    security: starttls
                    username: me
                    passwordFile: fastmail-password
                accounts:
                  - name: hotmail
                    email: me@hotmail.test
                    clientId: own-app
                    folders:
                      - source: junkemail
                        destination: fastmail
                        folder: Outlook Junk
                        removeSourceAfterSync: true
                      - source: Projects/2026
                        destination: dovecot
                        folder: Archive/Projects
                """);

        assertThat(config.pollInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(config.stateDirectory()).isEqualTo(Path.of("/var/lib/outlook-to-imap"));
        assertThat(config.database().username()).isEqualTo("outlook");
        assertThat(config.monitoring().healthPort()).isZero();
        assertThat(config.monitoring().healthBindAddress()).isEqualTo("0.0.0.0");
        assertThat(config.destinations().get("dovecot").port()).isEqualTo(143);
        assertThat(config.destinations().get("dovecot").security()).isEqualTo(Security.STARTTLS);
        Config.Account account = config.accounts().getFirst();
        assertThat(account.clientId()).isEqualTo("own-app");
        assertThat(account.tenant()).isEqualTo("organizations");
        assertThat(account.removesSource()).isTrue();
        assertThat(account.graphScopes()).containsExactly("https://graph.microsoft.com/Mail.ReadWrite");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> load("""
                pollInterval: PT30S
                destinations:
                  fastmail:
                    host: imap.fastmail.com
                    port: 99999
                    passwordFile: missing-file
                accounts:
                  - name: "bad name"
                    email: not-an-email
                    folders:
                      - source: junkemail
                        destination: nowhere
                  - name: second
                    email: second@hotmail.test
                    folders:
                      - source: inbox
                        destination: fastmail
                        folder: In
                      - source: INBOX
                        destination: fastmail
                        folder: In again
                """))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems()).containsExactlyInAnyOrder(
                        "pollInterval must be between PT1M and PT720H, got PT30S",
                        "destinations.fastmail.port must be between 1 and 65535, got 99999",
                        "destinations.fastmail.username is required",
                        "destinations.fastmail.passwordFile " + directory.resolve("missing-file") + " is not a readable file",
                        "accounts[0].name must use only letters, digits, '.', '_' and '-', got bad name",
                        "accounts[0].email must be the Microsoft account's email address",
                        "accounts[0] has no clientId: set microsoft.clientId or the account's clientId",
                        "accounts[0].folders[0].folder is required",
                        "accounts[0].folders[0].destination must name an entry under destinations, got nowhere",
                        "accounts[1] has no clientId: set microsoft.clientId or the account's clientId",
                        "accounts[1].folders[1].source INBOX is listed twice for this account"));
    }

    @Test
    void theEnvironmentCanSetTheDefaultStateDirectory() throws IOException {
        Path file = directory.resolve("config.yaml");
        Files.writeString(file, minimal(""));

        Config config = ConfigLoader.load(file, java.util.Map.of(ConfigLoader.STATE_DIRECTORY_VARIABLE, "/data"));

        assertThat(config.stateDirectory()).isEqualTo(Path.of("/data"));
        assertThat(config.database().url()).isEqualTo("jdbc:sqlite:/data/outlook-to-imap.db");
    }

    @Test
    void theHealthBindAddressIsAHostNameOrAnIpAddress() throws IOException {
        for (String host : new String[] {"localhost", "::", "[::1]", "192.168.1.10", "outlook-to-imap.internal"}) {
            assertThat(load(minimal("monitoring:\n  healthBindAddress: \"" + host + "\"\n")).monitoring().healthBindAddress())
                    .isEqualTo(host);
        }
        for (String bad : new String[] {"", " ", "0.0.0.0:8080", "http://0.0.0.0", "two words"}) {
            assertThatThrownBy(() -> load(minimal("monitoring:\n  healthBindAddress: \"" + bad + "\"\n")))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("monitoring.healthBindAddress must be a host name or IP address, got \"" + bad + "\"");
        }
    }

    @Test
    void rejectsAnIntervalAboveThirtyDays() {
        assertThatThrownBy(() -> load(minimal("pollInterval: P31D\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("pollInterval must be between");
    }

    @Test
    void rejectsUnknownSettingsSoTyposDoNotPassSilently() {
        assertThatThrownBy(() -> load(minimal("removeSourceAfterSinc: true\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("removeSourceAfterSinc");
    }

    @Test
    void rejectsAnotherDatabaseEngine() {
        assertThatThrownBy(() -> load(minimal("database:\n  url: jdbc:mysql://db/x\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("jdbc:sqlite: or jdbc:postgresql:");
    }

    @Test
    void reportsAMissingFile() {
        assertThatThrownBy(() -> ConfigLoader.load(directory.resolve("nope.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("cannot read");
    }

    private String minimal(String extra) {
        return extra + """
                microsoft:
                  clientId: app
                destinations:
                  fastmail:
                    host: imap.fastmail.com
                    username: me
                    passwordFile: fastmail-password
                accounts:
                  - name: hotmail
                    email: me@hotmail.test
                    folders:
                      - source: junkemail
                        destination: fastmail
                        folder: Junk
                """;
    }

    private Config load(String yaml) throws IOException {
        Path file = directory.resolve("config.yaml");
        Files.writeString(file, yaml);
        return ConfigLoader.load(file, java.util.Map.of());
    }
}
