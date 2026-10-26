package ch.fmartin.outlooktoimap.sync;

import java.time.Instant;

/** What one poll did for one account. {@code error} is null when nothing failed. */
public record AccountResult(
        String account, Instant startedAt, int imported, int removed, int skipped, int failed, String error) {

    public boolean succeeded() {
        return failed == 0;
    }
}
