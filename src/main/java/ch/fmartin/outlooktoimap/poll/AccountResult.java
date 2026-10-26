package ch.fmartin.outlooktoimap.poll;

import ch.fmartin.outlooktoimap.store.Counts;
import java.time.Instant;

/** What one poll did for one account. {@code error} is null when nothing failed. */
public record AccountResult(String account, Instant startedAt, Counts counts, String error) {

    public boolean succeeded() {
        return counts.failed() == 0;
    }
}
