package ch.fmartin.outlooktoimap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MainTest {

    @TempDir
    Path directory;

    private Path config;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void writeConfig() throws Exception {
        Files.writeString(directory.resolve("password"), "secret");
        config = directory.resolve("config.yaml");
        Files.writeString(config, """
                microsoft:
                  clientId: 11111111-2222-3333-4444-555555555555
                monitoring:
                  healthPort: 0
                destinations:
                  fastmail:
                    host: 127.0.0.1
                    port: 1
                    username: me
                    passwordFile: password
                accounts:
                  - name: hotmail
                    email: me@hotmail.test
                    folders:
                      - source: junkemail
                        destination: fastmail
                        folder: Outlook Junk
                """);
    }

    @Test
    void onceWithoutASavedLoginFailsTheAccountAndSaysHowToFixIt() {
        int exit = run("--config", config.toString(), "once");

        assertThat(exit).isEqualTo(Main.EXIT_ACCOUNT_FAILED);
        assertThat(out.toString()).contains("hotmail: 0 imported").contains("outlook-to-imap authorize hotmail");
    }

    @Test
    void statusShowsTheLastPoll() {
        run("--config", config.toString(), "once");

        assertThat(run("--config", config.toString(), "status")).isZero();
        assertThat(out.toString()).contains("hotmail: last success never").contains("1 failed").contains("error: account hotmail needs a new login");
    }

    @Test
    void anInvalidConfigurationExitsWithItsOwnCode() throws Exception {
        Files.writeString(config, "pollInterval: PT1S\n");

        assertThat(run("--config", config.toString(), "once")).isEqualTo(Main.EXIT_CONFIG);
        assertThat(err.toString()).contains("pollInterval must be between").contains("accounts must list");
    }

    @Test
    void authorizeNeedsAConfiguredAccount() {
        assertThat(run("--config", config.toString(), "authorize", "nobody")).isEqualTo(2);
        assertThat(err.toString()).contains("no account named nobody").contains("[hotmail]");
    }

    @Test
    void helpListsTheCommands() {
        assertThat(run("--help")).isZero();
        assertThat(out.toString()).contains("authorize").contains("run").contains("once").contains("status");
    }

    private int run(String... args) {
        CommandLine cli = Main.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }
}
