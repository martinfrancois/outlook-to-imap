package ch.fmartin.outlooktoimap.imap;

import java.util.Date;
import java.util.OptionalLong;

/** The three things the copy logic needs from an open connection to a destination. */
public interface DestinationConnection extends AutoCloseable {

    /**
     * Uploads {@code mime} unchanged, unread, with the arrival time Microsoft recorded. Returns the
     * new message's UID when the server reports one (UIDPLUS), otherwise empty.
     */
    OptionalLong append(String folderPath, byte[] mime, Date received);

    /** Whether the folder holds a message with this UID. */
    boolean contains(String folderPath, long uid);

    /** The UID of a message in the folder with exactly the fingerprinted content, if there is one. */
    OptionalLong find(String folderPath, Fingerprint fingerprint);

    @Override
    void close();
}
