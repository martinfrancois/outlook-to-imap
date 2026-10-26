package ch.fmartin.outlooktoimap;

import ch.fmartin.outlooktoimap.config.Config;
import ch.fmartin.outlooktoimap.config.Config.Account;
import ch.fmartin.outlooktoimap.config.ConfigException;
import ch.fmartin.outlooktoimap.config.ConfigLoader;
import ch.fmartin.outlooktoimap.microsoft.MicrosoftLogin;
import ch.fmartin.outlooktoimap.service.HealthServer;
import ch.fmartin.outlooktoimap.service.Heartbeat;
import ch.fmartin.outlooktoimap.service.PollService;
import ch.fmartin.outlooktoimap.store.Store;
import ch.fmartin.outlooktoimap.store.Store.AccountStatus;
import ch.fmartin.outlooktoimap.sync.RunReport;
import ch.fmartin.outlooktoimap.sync.Synchronizer;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "outlook-to-imap",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Copies mail from Microsoft accounts to IMAP folders through Microsoft Graph.",
        subcommands = CommandLine.HelpCommand.class)
public final class Main {

    /** Exit code when at least one account failed in a {@code once} poll. */
    static final int EXIT_ACCOUNT_FAILED = 1;
    /** Exit code for an invalid configuration file. */
    static final int EXIT_CONFIG = 2;
    /** Exit code when another process held the poll lease. */
    static final int EXIT_BUSY = 3;

    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(25);

    @Spec
    CommandSpec spec;

    @Option(names = {"-c", "--config"}, defaultValue = "${env:OUTLOOK_TO_IMAP_CONFIG:-config.yaml}",
            description = "Configuration file (default: $OUTLOOK_TO_IMAP_CONFIG, else ./config.yaml).")
    Path configFile;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((e, commandLine, parseResult) -> {
            if (e instanceof ConfigException) {
                commandLine.getErr().println(e.getMessage());
                return EXIT_CONFIG;
            }
            throw e;
        });
        return cli;
    }

    @Command(name = "authorize", description = {
        "Signs one account in with a code you enter on any device with a browser.",
        "Run it once per account, and again when the service reports that a login is needed."})
    int authorize(@Parameters(paramLabel = "ACCOUNT", description = "The account's name in the configuration.") String name) {
        Config config = config();
        Account account = config.accounts().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new CommandLine.ParameterException(spec.commandLine(),
                        "no account named " + name + " in " + configFile + "; known: "
                                + config.accounts().stream().map(Account::name).toList()));
        PrintWriter out = spec.commandLine().getOut();
        out.println("Requesting " + String.join(", ", account.graphScopes()) + " for " + account.email() + ".");
        new MicrosoftLogin(config.stateDirectory()).authorize(account, instructions -> {
            out.println(instructions);
            out.flush();
        });
        out.println("Saved the login of account " + name + ".");
        return 0;
    }

    @Command(name = "run", description = "Polls right away, then once per pollInterval, until stopped.")
    int run() throws InterruptedException {
        Config config = config();
        try (Store store = Store.open(config.database())) {
            PollService service = service(config, store);
            HealthServer health = config.monitoring().healthPort() == 0
                    ? null
                    : new HealthServer(config.monitoring().healthPort(), service, store);
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                service.stop(SHUTDOWN_GRACE);
                if (health != null) {
                    health.close();
                }
                stopped.countDown();
            }, "shutdown"));
            service.start();
            stopped.await();
        }
        return 0;
    }

    @Command(name = "once", description = "Polls every account once and exits; for checks, tests or an external scheduler.")
    int once(@Option(names = "--dry-run", description = "List what would be copied and removed without doing either.")
            boolean dryRun) {
        Config config = config();
        try (Store store = Store.open(config.database())) {
            PrintWriter out = spec.commandLine().getOut();
            Optional<RunReport> report = service(config, store).pollOnce(dryRun, line -> {
                out.println(line);
                out.flush();
            });
            if (report.isEmpty()) {
                return EXIT_BUSY;
            }
            out.println(report.get().summary());
            return report.get().succeeded() ? 0 : EXIT_ACCOUNT_FAILED;
        }
    }

    @Command(name = "status", description = "Shows each account's last poll as recorded in the database.")
    int status() {
        Config config = config();
        try (Store store = Store.open(config.database())) {
            List<AccountStatus> statuses = store.statuses();
            PrintWriter out = spec.commandLine().getOut();
            for (Account account : config.accounts()) {
                Optional<AccountStatus> status = statuses.stream().filter(s -> s.account().equals(account.name())).findFirst();
                if (status.isEmpty()) {
                    out.println(account.name() + ": never polled");
                    continue;
                }
                AccountStatus s = status.get();
                out.println(account.name() + ": last success " + (s.lastSuccessAt() == null ? "never" : s.lastSuccessAt())
                        + ", last attempt " + s.lastAttemptAt() + ", last poll " + s.imported() + " imported, " + s.removed()
                        + " removed, " + s.skipped() + " skipped, " + s.failed() + " failed");
                if (s.lastError() != null) {
                    out.println("  error: " + s.lastError());
                }
            }
            return 0;
        }
    }

    private Config config() {
        return ConfigLoader.load(configFile);
    }

    private static PollService service(Config config, Store store) {
        MicrosoftLogin login = new MicrosoftLogin(config.stateDirectory());
        return new PollService(new Synchronizer(config, store, login), store,
                new Heartbeat(config.monitoring().heartbeatUrl()), config.pollInterval(), Clock.systemUTC());
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[] {"outlook-to-imap " + (version == null ? "development build" : version)};
        }
    }
}
