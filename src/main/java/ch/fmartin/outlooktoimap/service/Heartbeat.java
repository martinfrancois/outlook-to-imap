package ch.fmartin.outlooktoimap.service;

import ch.fmartin.outlooktoimap.poll.PollReport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pings a healthchecks.io-style URL after every poll: the URL itself when every account worked,
 * the URL with {@code /fail} and the summary as body when one did not. The monitoring service
 * alerts on a failure ping and on pings that stop coming.
 */
public final class Heartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(Heartbeat.class);

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Heartbeat(String url) {
        this.url = url;
    }

    public void report(PollReport report) {
        if (url == null) {
            return;
        }
        String target = report.succeeded() ? url : url.replaceAll("/+$", "") + "/fail";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(report.summary()))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                LOG.warn("heartbeat ping returned HTTP {}", response.statusCode());
            }
        } catch (IOException e) {
            LOG.warn("heartbeat ping failed: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
