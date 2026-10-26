package ch.fmartin.outlooktoimap.sync;

import java.util.List;
import java.util.stream.Collectors;

/** The outcome of one poll over all accounts. */
public record RunReport(List<AccountResult> accounts) {

    public boolean succeeded() {
        return accounts.stream().allMatch(AccountResult::succeeded);
    }

    /** One line per account, free of message content, for logs and the heartbeat. */
    public String summary() {
        return accounts.stream()
                .map(a -> a.account() + ": " + a.imported() + " imported, " + a.removed() + " removed, " + a.skipped()
                        + " skipped, " + a.failed() + " failed" + (a.error() == null ? "" : " (" + a.error() + ")"))
                .collect(Collectors.joining("\n"));
    }
}
