package ch.fmartin.outlooktoimap.imap;

import java.util.Date;
import java.util.OptionalLong;

/** The three things the copy logic needs from a destination mailbox. */
public interface DestinationMailbox extends AutoCloseable {

    /**
     * Uploads {@code mime} unchanged, unread, with the arrival time Microsoft recorded. Returns the
     * new message's UID when the server reports one (UIDPLUS), otherwise empty.
     */
    OptionalLong append(String folderPath, byte[] mime, Date received);

    /** Whether the folder holds a message with this UID. */
    boolean contains(String folderPath, long uid);

    /** The UID of a message in the folder whose bytes equal {@code mime}, if there is one. */
    OptionalLong find(String folderPath, byte[] mime);

    @Override
    void close();
}
