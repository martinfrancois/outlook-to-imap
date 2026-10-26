package ch.fmartin.outlooktoimap.service;

import ch.fmartin.outlooktoimap.store.Store;
import ch.fmartin.outlooktoimap.store.Store.AccountStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /health}: HTTP 200 while polls make progress, 503 otherwise, and per-account status
 * as JSON either way. The container health check calls it.
 */
public final class HealthServer implements AutoCloseable {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;

    public HealthServer(int port, PollService service, Store store) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen on port " + port + " for /health", e);
        }
        server.createContext("/health", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                boolean healthy = service.healthy();
                byte[] body = JSON.writeValueAsBytes(body(healthy, service, store.statuses()));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(healthy ? 200 : 503, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (RuntimeException e) {
                byte[] body = ("{\"status\":\"down\",\"error\":" + JSON.writeValueAsString(e.toString()) + "}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    private static Map<String, Object> body(boolean healthy, PollService service, List<AccountStatus> statuses) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", healthy ? "up" : "down");
        body.put("lastPollFinishedAt", service.lastFinished() == null ? null : service.lastFinished().toString());
        body.put("accounts", statuses.stream().map(s -> {
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("account", s.account());
            account.put("lastAttemptAt", s.lastAttemptAt().toString());
            account.put("lastSuccessAt", s.lastSuccessAt() == null ? null : s.lastSuccessAt().toString());
            account.put("imported", s.imported());
            account.put("removed", s.removed());
            account.put("skipped", s.skipped());
            account.put("failed", s.failed());
            account.put("lastError", s.lastError());
            return account;
        }).toList());
        return body;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
