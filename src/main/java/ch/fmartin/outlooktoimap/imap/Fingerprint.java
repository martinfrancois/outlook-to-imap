package ch.fmartin.outlooktoimap.imap;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetHeaders;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.eclipse.angus.mail.util.CRLFOutputStream;

/**
 * What finds a copy at a destination again: the Message-ID and Date headers narrow the search,
 * and the hash and size of the exact bytes decide. Spam often reuses Message-IDs, so the
 * headers alone never count as a match.
 */
public record Fingerprint(String messageId, String date, String contentHash, long size) {

    // Another algorithm would no longer match the hashes PENDING receipts recorded, and those messages would be uploaded twice, see docs/adr/0014-verify-each-upload-and-settle-unknown-ones-by-content.md
    private static final String HASH_ALGORITHM = "SHA3-256";

    /**
     * The bytes the server will store for {@code mime}. IMAP requires CRLF line endings and Angus
     * Mail converts any others on the way out, so fingerprints are taken over the converted form.
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

    /** The fingerprint of bytes already in {@link #canonical canonical} form. */
    public static Fingerprint of(byte[] canonical) {
        String messageId = null;
        String date = null;
        try {
            InternetHeaders headers = new InternetHeaders(new ByteArrayInputStream(canonical));
            messageId = headers.getHeader("Message-ID", null);
            date = headers.getHeader("Date", null);
        } catch (MessagingException e) {
            // Unparseable headers only cost the narrow search; the content still decides.
        }
        return new Fingerprint(searchable(messageId), searchable(date), hash(canonical), canonical.length);
    }

    /** The same search headers with another content, such as the one recorded before an upload. */
    public Fingerprint withContent(String contentHash, long size) {
        return new Fingerprint(messageId, date, contentHash, size);
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The part of a header value to search for. SEARCH HEADER matches substrings; GreenMail compares
     * a folded header as stored and Dovecot compares it unfolded, so only a part without the line
     * break matches on both. The longest such part narrows the search the most. The probe that
     * showed this is in docs/adr/0014-verify-each-upload-and-settle-unknown-ones-by-content.md.
     */
    private static String searchable(String value) {
        if (value == null) {
            return null;
        }
        // Null when every line is blank, so the search falls back to the next header.
        String longest = null;
        for (String line : value.lines().toList()) {
            String part = line.strip();
            if (!part.isEmpty() && (longest == null || part.length() > longest.length())) {
                longest = part;
            }
        }
        return longest;
    }
}
