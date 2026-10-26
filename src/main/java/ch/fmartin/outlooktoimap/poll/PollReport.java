package ch.fmartin.outlooktoimap.poll;

import java.util.List;
import java.util.stream.Collectors;

/** The outcome of one poll over all accounts. */
public record PollReport(List<AccountResult> accounts) {

    public boolean succeeded() {
        return accounts.stream().allMatch(AccountResult::succeeded);
    }

    /** One line per account, free of message content, for logs and the heartbeat. */
    public String summary() {
        return accounts.stream()
                .map(a -> a.account() + ": " + a.counts() + (a.error() == null ? "" : " (" + a.error() + ")"))
                .collect(Collectors.joining("\n"));
    }
}
