package ch.fmartin.outlooktoimap.store;

/** What one poll did to the messages of one account. */
public record Counts(int imported, int removed, int skipped, int failed) {

    @Override
    public String toString() {
        return imported + " imported, " + removed + " removed, " + skipped + " skipped, " + failed + " failed";
    }
}
