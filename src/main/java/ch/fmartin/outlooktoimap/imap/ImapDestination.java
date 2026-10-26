package ch.fmartin.outlooktoimap.imap;

import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.Security;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.StoreClosedException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.HeaderTerm;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.util.CRLFOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One IMAP server, reached over TLS or STARTTLS and nothing else. It stores messages byte for byte
 * with APPEND and finds them again by content, which is how an upload whose reply never arrived
 * gets settled without uploading the message twice.
 */
public final class ImapDestination implements DestinationMailbox {

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
            store.connect(destination.host(), destination.port(), destination.username(), password(destination));
            return new ImapDestination(destination, store);
        } catch (MessagingException e) {
            throw new DestinationUnavailableException(destination.name(), e);
        }
    }

    private static String password(Destination destination) {
        try {
            return Files.readString(destination.passwordFile(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the password file of destination " + destination.name(), e);
        }
    }

    /**
     * The bytes the server will store for {@code mime}. IMAP requires CRLF line endings and Angus
     * Mail converts any others on the way out, so hashes are taken over the converted form.
     */
    public static byte[] canonical(byte[] mime) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(mime.length + 64);
        try (OutputStream crlf = new CRLFOutputStream(out)) {
            crlf.write(mime);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    /**
     * The search narrows candidates by Message-ID, or by Date when there is none, and the SHA-256
     * of each candidate's full content decides.
     */
    @Override
    public OptionalLong find(String folderPath, byte[] mime) {
        String sha = sha256(mime);
        return run(folderPath, folder -> {
            Message[] candidates = candidates(folder, mime);
            for (Message candidate : candidates) {
                if (candidate.getSize() != mime.length) {
                    continue;
                }
                int number = candidate.getMessageNumber();
                byte[] stored = (byte[]) folder.doCommand(protocol -> {
                    BODY body = protocol.peekBody(number, "");
                    return body == null ? null : body.getByteArrayInputStream().readAllBytes();
                });
                if (stored != null && sha.equals(sha256(stored))) {
                    return OptionalLong.of(folder.getUID(candidate));
                }
            }
            return OptionalLong.empty();
        });
    }

    private static Message[] candidates(IMAPFolder folder, byte[] mime) throws MessagingException {
        MimeMessage parsed = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime));
        for (String header : new String[] {"Message-ID", "Date"}) {
            String value = parsed.getHeader(header, null);
            if (value != null && !value.isBlank()) {
                return folder.search(new HeaderTerm(header, value.strip()));
            }
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
        public DestinationUnavailableException(String name, Throwable cause) {
            super("destination " + name + " is unavailable: " + cause.getMessage(), cause);
        }
    }
}
