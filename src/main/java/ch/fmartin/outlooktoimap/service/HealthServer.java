package ch.fmartin.outlooktoimap.service;

import ch.fmartin.outlooktoimap.store.Store;
import ch.fmartin.outlooktoimap.store.Store.AccountStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /health}: HTTP 200 while polls make progress, 503 otherwise, and per-account status
 * as JSON either way. The container health check calls it.
 */
public final class HealthServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HealthServer.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    // Without these limits a stalled client holds its connection forever, see docs/adr/0024-a-virtual-thread-per-health-request-and-jdk-time-limits.md
    // Seconds a client may take to send its request or read the reply; the JDK reads both once, when it creates its first server.
    static final int CLIENT_TIMEOUT_SECONDS = 10;

    static {
        setIfAbsent("sun.net.httpserver.maxReqTime", CLIENT_TIMEOUT_SECONDS);
        setIfAbsent("sun.net.httpserver.maxRspTime", CLIENT_TIMEOUT_SECONDS);
    }

    // A value set on the command line wins.
    private static void setIfAbsent(String property, int seconds) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, Integer.toString(seconds));
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;

    /** The body of every reply. {@code error} is set only when the status could not be read. */
    record Health(String status, Instant lastPollFinishedAt, List<AccountStatus> accounts, String error) {}

    public HealthServer(InetSocketAddress address, PollService service, Store store) {
        try {
            server = HttpServer.create(address, 0);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen on " + address + " for /health", e);
        }
        // Without an executor one client that stops mid-request blocks all others on the single dispatcher thread, see docs/adr/0024-a-virtual-thread-per-health-request-and-jdk-time-limits.md
        // Virtual threads are daemon threads, and unlike a fixed pool a few slow clients cannot use them all up.
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("health-", 0).factory());
        server.setExecutor(executor);
        server.createContext("/health", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                Health health;
                try {
                    boolean healthy = service.healthy();
                    health = new Health(healthy ? "up" : "down", service.lastFinished(), store.statuses(), null);
                } catch (RuntimeException e) {
                    LOG.warn("/health could not read the status", e);
                    health = new Health("down", service.lastFinished(), List.of(), e.toString());
                }
                send(exchange, "up".equals(health.status()) ? 200 : 503, JSON.writeValueAsBytes(health));
            }
        });
        server.start();
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
