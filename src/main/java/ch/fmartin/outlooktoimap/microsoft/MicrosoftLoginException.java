package ch.fmartin.outlooktoimap.microsoft;

/** A sign-in problem that a later attempt may not have, such as a network failure. */
public final class MicrosoftLoginException extends RuntimeException {

    public MicrosoftLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
