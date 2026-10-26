package ch.fmartin.outlooktoimap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** MIME messages shaped like the junk Outlook hands out: multipart, with an attachment. */
public final class TestMessages {

    private TestMessages() {}

    public static byte[] mime(String id) {
        return mime("<" + id + "@example.test>", "Win a prize " + id, "Wed, 01 Oct 2026 08:00:00 +0000");
    }

    public static byte[] mime(String messageId, String subject, String date) {
        String attachment = Base64.getMimeEncoder().encodeToString(("MZ fake executable for " + subject).getBytes(StandardCharsets.UTF_8));
        String headers = (messageId == null ? "" : "Message-ID: " + messageId + "\r\n")
                + "From: \"Prize Desk\" <winner@spam.example>\r\n"
                + "Reply-To: claims@spam.example\r\n"
                + "To: someone@hotmail.example\r\n"
                + "Subject: " + subject + "\r\n"
                + (date == null ? "" : "Date: " + date + "\r\n")
                + "X-Microsoft-Antispam: BCL:9;\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"b1\"\r\n";
        String body = "\r\n--b1\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n\r\n"
                + "<p>Click <a href=\"https://spam.example/claim\">here</a> <img src=\"https://spam.example/t.gif\"></p>\r\n"
                + "--b1\r\n"
                + "Content-Type: application/octet-stream; name=\"prize.exe\"\r\n"
                + "Content-Disposition: attachment; filename=\"prize.exe\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + attachment + "\r\n"
                + "--b1--\r\n";
        return (headers + body).getBytes(StandardCharsets.UTF_8);
    }
}
