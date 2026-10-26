package ch.fmartin.outlooktoimap;

import ch.fmartin.outlooktoimap.config.Config.Database;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The two supported database engines, for tests that must pass on both. PostgreSQL runs once per
 * test JVM in a container; each test gets its own database in it.
 */
public final class Databases {

    private static PostgreSQLContainer postgres;

    private Databases() {}

    public enum Engine { SQLITE, POSTGRESQL }

    public static Stream<Engine> engines() {
        return Stream.of(Engine.SQLITE, Engine.POSTGRESQL);
    }

    public static Database fresh(Engine engine, Path directory) {
        return switch (engine) {
            case SQLITE -> new Database("jdbc:sqlite:" + directory.resolve("state/" + UUID.randomUUID() + ".db"), null, null);
            case POSTGRESQL -> freshPostgres(directory);
        };
    }

    private static synchronized Database freshPostgres(Path directory) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer("postgres:18-alpine");
            postgres.start();
        }
        String name = "t" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = postgres.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        try {
            Path password = directory.resolve("postgres-password");
            Files.writeString(password, postgres.getPassword() + "\n");
            String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
            return new Database(url, postgres.getUsername(), password);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
