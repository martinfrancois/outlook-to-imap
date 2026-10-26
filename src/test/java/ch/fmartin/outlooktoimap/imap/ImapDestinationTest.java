package ch.fmartin.outlooktoimap.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fmartin.outlooktoimap.FakeImap;
import ch.fmartin.outlooktoimap.TestMessages;
import ch.fmartin.outlooktoimap.config.Config.Destination;
import ch.fmartin.outlooktoimap.config.Config.Security;
import ch.fmartin.outlooktoimap.imap.ImapDestination.DestinationUnavailableException;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImapDestinationTest {

    private final FakeImap imap = new FakeImap();

    @TempDir
    Path directory;

    @AfterEach
    void stop() {
        imap.close();
    }

    @Test
    void storesTheMessageByteForByteUnreadWithItsArrivalTimeInANewFolder() throws Exception {
        byte[] mime = TestMessages.mime("m1");
        Date received = Date.from(Instant.parse("2026-10-01T08:00:00Z"));

        try (ImapDestination destination = imap.connect(destination())) {
            OptionalLong uid = destination.append("Outlook/Junk", mime, received);

            assertThat(uid).isPresent();
            assertThat(destination.contains("Outlook/Junk", uid.getAsLong())).isTrue();
            assertThat(destination.contains("Outlook/Junk", uid.getAsLong() + 1)).isFalse();
        }
        assertThat(imap.messages("Outlook.Junk")).hasSize(1);
        assertThat(new String(imap.messages("Outlook.Junk").getFirst(), StandardCharsets.UTF_8))
                .isEqualTo(new String(mime, StandardCharsets.UTF_8));
        StoredMessage stored = imap.greenMail.getManagers().getImapHostManager()
                .getFolder(imap.greenMail.getManagers().getUserManager().getUser(FakeImap.USER), "Outlook.Junk")
                .getMessages().getFirst();
        assertThat(stored.getReceivedDate()).isEqualTo(received);
        assertThat(stored.getFlags().contains(Flags.Flag.SEEN)).isFalse();
    }

    @Test
    void findsAStoredMessageByItsContent() {
        byte[] mime = TestMessages.mime("m1");
        try (ImapDestination destination = imap.connect(destination())) {
            assertThat(destination.find("Junk", fingerprint(mime))).isEmpty();
            long uid = destination.append("Junk", mime, null).getAsLong();

            assertThat(destination.find("Junk", fingerprint(mime))).hasValue(uid);
        }
    }

    @Test
    void findsAMessageWhoseMessageIdIsFoldedOverTwoLines() {
        byte[] mime = TestMessages.mime("<prize-2026-10-01-0800-0000-claim-now\r\n .winner@spam.example>", "folded",
                "Wed, 01 Oct 2026\r\n 08:00:00 +0000");
        // Dovecot matches SEARCH HEADER against the unfolded header, so a search string holding the
        // line break finds nothing there. GreenMail matches the folded text, so it can't show that.
        assertThat(fingerprint(mime).messageId()).doesNotContain("\r", "\n");
        assertThat(fingerprint(mime).date()).doesNotContain("\r", "\n");
        try (ImapDestination destination = imap.connect(destination())) {
            long uid = destination.append("Junk", mime, null).getAsLong();

            assertThat(destination.find("Junk", fingerprint(mime))).hasValue(uid);
        }
    }

    @Test
    void lookingForACopyNeverCreatesAFolder() {
        try (ImapDestination destination = imap.connect(destination())) {
            assertThat(destination.find("Old Junk", fingerprint(TestMessages.mime("m1")))).isEmpty();
            assertThat(destination.contains("Old Junk", 1)).isFalse();
        }

        assertThat(imap.greenMail.getManagers().getImapHostManager()
                .getFolder(imap.greenMail.getManagers().getUserManager().getUser(FakeImap.USER), "Old Junk")).isNull();
    }

    @Test
    void aSharedMessageIdWithDifferentContentIsNotAMatch() {
        // Spam often reuses Message-IDs, so the id alone must not count as "already uploaded".
        byte[] first = TestMessages.mime("<same@spam.example>", "first", "Wed, 01 Oct 2026 08:00:00 +0000");
        byte[] second = TestMessages.mime("<same@spam.example>", "second", "Wed, 01 Oct 2026 08:00:00 +0000");
        try (ImapDestination destination = imap.connect(destination())) {
            destination.append("Junk", first, null);

            assertThat(destination.find("Junk", fingerprint(second))).isEmpty();
        }
    }

    @Test
    void findsMessagesWithoutMessageIdByDateAndWithoutEitherByScanning() {
        byte[] noId = TestMessages.mime(null, "no id", "Thu, 02 Oct 2026 09:00:00 +0000");
        byte[] nothing = TestMessages.mime(null, "no id, no date", null);
        try (ImapDestination destination = imap.connect(destination())) {
            long a = destination.append("Junk", noId, null).getAsLong();
            long b = destination.append("Junk", nothing, null).getAsLong();

            assertThat(destination.find("Junk", fingerprint(noId))).hasValue(a);
            assertThat(destination.find("Junk", fingerprint(nothing))).hasValue(b);
        }
    }

    @Test
    void canonicalFormUsesCrlfLineEndings() {
        byte[] lf = "Subject: x\nTo: a@b\n\nbody\n".getBytes(StandardCharsets.US_ASCII);

        assertThat(new String(Fingerprint.canonical(lf), StandardCharsets.US_ASCII))
                .isEqualTo("Subject: x\r\nTo: a@b\r\n\r\nbody\r\n");
        try (ImapDestination destination = imap.connect(destination())) {
            byte[] canonical = Fingerprint.canonical(lf);
            destination.append("Junk", canonical, null);
            assertThat(destination.find("Junk", fingerprint(canonical))).isPresent();
        }
    }

    @Test
    void aWrongPasswordIsReportedAsUnavailable() throws Exception {
        Destination good = destination();
        Path wrong = directory.resolve("wrong");
        Files.writeString(wrong, "nope");
        Destination bad = new Destination(good.name(), good.host(), good.port(), good.security(), good.username(), wrong);

        assertThatThrownBy(() -> imap.connect(bad)).isInstanceOf(DestinationUnavailableException.class);
    }

    @Test
    void anUnreadablePasswordFileMakesOnlyThisDestinationUnavailable() {
        Destination good = destination();
        Destination missing = new Destination(good.name(), good.host(), good.port(), good.security(), good.username(),
                directory.resolve("deleted-password"));

        assertThatThrownBy(() -> imap.connect(missing))
                .isInstanceOf(DestinationUnavailableException.class)
                .hasMessageContaining("deleted-password");
    }

    @Test
    void refusesToLogInWithoutTls() {
        GreenMail plain = new GreenMail(ServerSetupTest.IMAP.dynamicPort());
        plain.start();
        try {
            plain.setUser("me@destination.test", FakeImap.USER, FakeImap.PASSWORD);
            Destination good = destination();
            Destination starttls = new Destination(good.name(), good.host(), plain.getImap().getPort(), Security.STARTTLS,
                    good.username(), good.passwordFile());

            assertThatThrownBy(() -> imap.connect(starttls)).isInstanceOf(DestinationUnavailableException.class);
            assertThat(plain.getManagers().getUserManager().getUser(FakeImap.USER)).isNotNull();
        } finally {
            plain.stop();
        }
    }

    private Destination destination() {
        return imap.destination("fastmail", directory);
    }

    private static Fingerprint fingerprint(byte[] mime) {
        return Fingerprint.of(Fingerprint.canonical(mime));
    }
}
