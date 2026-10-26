package ch.fmartin.outlooktoimap;

import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.Security;
import ch.fmartin.outlooktoimap.imap.ImapDestination;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** A GreenMail IMAPS server with one mailbox, and a way to read back what landed in it. */
public final class FakeImap implements AutoCloseable {

    public static final String USER = "me";
    public static final String PASSWORD = "app-password";

    public final GreenMail greenMail;
    private final ServerSetup setup;

    public FakeImap() {
        setup = ServerSetupTest.IMAPS.dynamicPort();
        greenMail = new GreenMail(setup);
        greenMail.start();
        greenMail.setUser("me@destination.test", USER, PASSWORD);
    }

    public Destination destination(String name, Path directory) {
        try {
            Path password = directory.resolve(name + "-password");
            Files.writeString(password, PASSWORD + "\n");
            return new Destination(name, "127.0.0.1", greenMail.getImaps().getPort(), Security.TLS, USER, password);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** GreenMail's certificate is self-signed, so tests trust it explicitly. */
    public static Properties trustTestCertificate() {
        Properties p = new Properties();
        p.put("mail.imap.ssl.trust", "*");
        p.put("mail.imap.ssl.checkserveridentity", "false");
        return p;
    }

    public ImapDestination connect(Destination destination) {
        return ImapDestination.connect(destination, trustTestCertificate());
    }

    /** The raw bytes of every message in {@code folder}, in order. */
    public List<byte[]> messages(String folder) {
        try {
            Store store = greenMail.getImaps().createStore();
            store.connect(USER, PASSWORD);
            try {
                Folder f = store.getFolder(folder);
                if (!f.exists()) {
                    return List.of();
                }
                f.open(Folder.READ_ONLY);
                List<byte[]> result = new ArrayList<>();
                for (Message m : f.getMessages()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    m.writeTo(out);
                    result.add(out.toByteArray());
                }
                f.close(false);
                return result;
            } finally {
                store.close();
            }
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deletes every message in {@code folder}, as a user cleaning up spam would. */
    public void deleteAll(String folder) {
        try {
            Store store = greenMail.getImaps().createStore();
            store.connect(USER, PASSWORD);
            Folder f = store.getFolder(folder);
            f.open(Folder.READ_WRITE);
            for (Message m : f.getMessages()) {
                m.setFlag(jakarta.mail.Flags.Flag.DELETED, true);
            }
            f.close(true);
            store.close();
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        greenMail.stop();
    }
}
