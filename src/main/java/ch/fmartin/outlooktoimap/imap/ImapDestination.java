package ch.fmartin.outlooktoimap.imap;

import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.Security;
import ch.fmartin.outlooktoimap.config.SecretFile;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.StoreClosedException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.HeaderTerm;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One IMAP server, reached over TLS or STARTTLS and nothing else. It stores messages byte for byte
 * with APPEND and finds them again by content, which is how an upload whose reply never arrived
 * gets settled without uploading the message twice.
 */
public final class ImapDestination implements DestinationConnection {

    private static final Logger LOG = LoggerFactory.getLogger(ImapDestination.class);
    private static final String TIMEOUT_MILLIS = "120000";

    private final Destination destination;
    private final IMAPStore store;
    private final Map<String, IMAPFolder> folders = new HashMap<>();

    private ImapDestination(Destination destination, IMAPStore store) {
        this.destination = destination;
        this.store = store;
    }

    public static ImapDestination connect(Destination destination) {
        return connect(destination, new Properties());
    }

    /** {@code overrides} lets tests trust a local server's self-signed certificate. */
    public static ImapDestination connect(Destination destination, Properties overrides) {
        Properties properties = new Properties();
        properties.put("mail.imap.ssl.checkserveridentity", "true");
        properties.put("mail.imap.connectiontimeout", "30000");
        properties.put("mail.imap.timeout", TIMEOUT_MILLIS);
        properties.put("mail.imap.writetimeout", TIMEOUT_MILLIS);
        if (destination.security() == Security.TLS) {
            properties.put("mail.imap.ssl.enable", "true");
        } else {
            properties.put("mail.imap.starttls.enable", "true");
            properties.put("mail.imap.starttls.required", "true");
        }
        properties.putAll(overrides);
        Session session = Session.getInstance(properties);
        try {
            IMAPStore store = (IMAPStore) session.getStore("imap");
            store.connect(destination.host(), destination.port(), destination.username(),
                    SecretFile.read(destination.passwordFile()));
            return new ImapDestination(destination, store);
        } catch (MessagingException | UncheckedIOException e) {
            throw new DestinationUnavailableException(destination.name(), e);
        }
    }


    @Override
    public OptionalLong append(String folderPath, byte[] mime, Date received) {
        return run(folderPath, folder -> {
            AppendUID[] uids = folder.appendUIDMessages(new Message[] {new RawMessage(mime, received)});
            return uids != null && uids.length == 1 && uids[0] != null ? OptionalLong.of(uids[0].uid) : OptionalLong.empty();
        });
    }

    @Override
    public boolean contains(String folderPath, long uid) {
        return run(folderPath, folder -> folder.getMessageByUID(uid) != null);
    }

    @Override
    public OptionalLong find(String folderPath, Fingerprint fingerprint) {
        return run(folderPath, folder -> {
            for (Message candidate : candidates(folder, fingerprint)) {
                if (candidate.getSize() != fingerprint.size()) {
                    continue;
                }
                int number = candidate.getMessageNumber();
                byte[] stored = (byte[]) folder.doCommand(protocol -> {
                    BODY body = protocol.peekBody(number, "");
                    return body == null ? null : body.getByteArrayInputStream().readAllBytes();
                });
                if (stored != null && fingerprint.contentHash().equals(Fingerprint.hash(stored))) {
                    return OptionalLong.of(folder.getUID(candidate));
                }
            }
            return OptionalLong.empty();
        });
    }

    /** Narrows by Message-ID, or by Date for a message without one, or else takes the whole folder. */
    private static Message[] candidates(IMAPFolder folder, Fingerprint fingerprint) throws MessagingException {
        if (fingerprint.messageId() != null) {
            return folder.search(new HeaderTerm("Message-ID", fingerprint.messageId()));
        }
        if (fingerprint.date() != null) {
            return folder.search(new HeaderTerm("Date", fingerprint.date()));
        }
        return folder.getMessages();
    }

    @Override
    public void close() {
        for (IMAPFolder folder : folders.values()) {
            try {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            } catch (MessagingException e) {
                LOG.debug("closing folder {} failed", folder.getFullName(), e);
            }
        }
        try {
            store.close();
        } catch (MessagingException e) {
            LOG.debug("closing destination {} failed", destination.name(), e);
        }
    }

    private <T> T run(String folderPath, FolderAction<T> action) {
        try {
            return action.apply(folder(folderPath));
        } catch (StoreClosedException | FolderClosedException e) {
            throw new DestinationUnavailableException(destination.name(), e);
        } catch (MessagingException e) {
            if (e.getCause() instanceof ConnectionException || e.getCause() instanceof IOException) {
                throw new DestinationUnavailableException(destination.name(), e);
            }
            throw new DestinationException("destination " + destination.name() + " refused the request: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new DestinationUnavailableException(destination.name(), e);
        }
    }

    private IMAPFolder folder(String path) throws MessagingException {
        IMAPFolder open = folders.get(path);
        if (open != null && open.isOpen()) {
            return open;
        }
        char separator = store.getDefaultFolder().getSeparator();
        IMAPFolder folder = (IMAPFolder) store.getFolder(path.replace('/', separator));
        if (!folder.exists()) {
            if (!folder.create(Folder.HOLDS_MESSAGES)) {
                throw new MessagingException("cannot create folder " + path);
            }
            LOG.info("created folder \"{}\" on destination {}", path, destination.name());
        }
        folder.open(Folder.READ_ONLY);
        folders.put(path, folder);
        return folder;
    }

    @FunctionalInterface
    private interface FolderAction<T> {
        T apply(IMAPFolder folder) throws MessagingException, IOException;
    }

    /**
     * A message that writes the downloaded bytes as they are. Parsing and re-serialising them with
     * MimeMessage could reorder or re-encode parts.
     */
    private static final class RawMessage extends MimeMessage {
        private final byte[] mime;
        private final Date received;

        RawMessage(byte[] mime, Date received) {
            super((Session) null);
            this.mime = mime;
            this.received = received;
        }

        @Override
        public void writeTo(OutputStream os) throws IOException {
            os.write(mime);
        }

        @Override
        public void writeTo(OutputStream os, String[] ignoreList) throws IOException {
            os.write(mime);
        }

        @Override
        public Date getReceivedDate() {
            return received;
        }
    }

    /** The server refused one request; the connection is still usable. */
    public static class DestinationException extends RuntimeException {
        public DestinationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The connection failed or broke; nothing else can go to this server in this poll. */
    public static final class DestinationUnavailableException extends DestinationException {
        private final String destination;

        public DestinationUnavailableException(String destination, Throwable cause) {
            super("destination " + destination + " is unavailable: " + cause.getMessage(), cause);
            this.destination = destination;
        }

        public String destination() {
            return destination;
        }
    }
}
