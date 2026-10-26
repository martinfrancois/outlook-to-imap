package ch.fmartin.outlooktoimap.microsoft;

/**
 * The saved login can no longer get a token: it never existed, Microsoft revoked it, or the
 * account now needs a permission it was never granted. Only a new {@code authorize} fixes it.
 */
public final class ReauthorizationRequiredException extends RuntimeException {

    public ReauthorizationRequiredException(String accountName, String reason, Throwable cause) {
        super("account " + accountName + " needs a new login (" + reason + "): run `outlook-to-imap authorize "
                + accountName + "`", cause);
    }
}
