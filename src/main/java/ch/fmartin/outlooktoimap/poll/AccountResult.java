package ch.fmartin.outlooktoimap.poll;

import ch.fmartin.outlooktoimap.store.Counts;
import java.time.Instant;

/**
 * What one poll did for one account. {@code error} is null when nothing failed; {@code interrupted}
 * means the poll was told to stop, by a shutdown or a lost lease, before every folder was read.
 */
public record AccountResult(String account, Instant startedAt, Counts counts, String error, boolean interrupted) {

    public boolean succeeded() {
        return counts.failed() == 0 && !interrupted;
    }
}
