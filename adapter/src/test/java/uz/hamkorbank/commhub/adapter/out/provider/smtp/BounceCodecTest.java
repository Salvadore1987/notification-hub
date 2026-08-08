package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** Reading a non-delivery report the way the mail servers of the world actually write them (EM-02). */
class BounceCodecTest {

    private final BounceCodec codec = new BounceCodec();

    @Test
    @DisplayName("EM-02: an RFC 3464 report names the message, the address and the enhanced status")
    void readsADeliveryStatusNotification() throws Exception {
        // Arrange
        MessageId messageId = MessageId.newId();

        // Act
        BounceReport report = codec.parse(dsn(messageId, "failed", "5.1.1")).orElseThrow();

        // Assert
        assertThat(report.hubMessageIdOptional()).contains(messageId);
        assertThat(report.recipient()).isEqualTo("client@example.com");
        assertThat(report.action()).isEqualTo("failed");
        assertThat(report.status()).isEqualTo("5.1.1");
        assertThat(report.diagnosticCode()).contains("550");
        assertThat(BounceCatalog.statusOf(report)).contains(MessageStatus.UNDELIVERED);
        assertThat(BounceCatalog.suppressionOf(report)).contains(SuppressionReason.HARD_BOUNCE);
    }

    @Test
    @DisplayName("EM-02: a full mailbox is undelivered but does not cost the customer their address")
    void aFullMailboxIsNotSuppressed() throws Exception {
        // Act
        BounceReport report =
                codec.parse(dsn(MessageId.newId(), "failed", "5.2.2")).orElseThrow();

        // Assert
        assertThat(BounceCatalog.statusOf(report)).contains(MessageStatus.UNDELIVERED);
        assertThat(BounceCatalog.suppressionOf(report)).isEmpty();
    }

    @Test
    @DisplayName("EM-02: a delayed report is applied as nothing — 'still trying' is not an outcome")
    void aDelayIsNotAnOutcome() throws Exception {
        // Act
        BounceReport report =
                codec.parse(dsn(MessageId.newId(), "delayed", "4.4.1")).orElseThrow();

        // Assert
        assertThat(BounceCatalog.statusOf(report)).isEmpty();
        assertThat(BounceCatalog.suppressionOf(report)).isEmpty();
    }

    @Test
    @DisplayName("EM-02: a human-readable bounce is read when it returns the Hub header and a status code")
    void readsALegacyBounce() throws Exception {
        // Arrange
        MessageId messageId = MessageId.newId();
        String body = """
                From: postmaster@example.com
                To: bounces@hamkorbank.uz
                Subject: Undeliverable: Выписка
                Content-Type: text/plain; charset=UTF-8

                Your message could not be delivered. The error was 5.1.1 unknown user.

                ----- Original message headers -----
                Message-ID: <%s@hamkorbank.uz>
                X-Comm-Message-Id: %s
                """.formatted(messageId, messageId);

        // Act
        BounceReport report = codec.parse(message(body)).orElseThrow();

        // Assert
        assertThat(report.hubMessageIdOptional()).contains(messageId);
        assertThat(report.status()).isEqualTo("5.1.1");
        assertThat(BounceCatalog.isHardBounce(report)).isTrue();
    }

    @Test
    @DisplayName("EM-02: a message that names nothing is left alone rather than guessed at")
    void anUnattributableMessageIsIgnored() throws Exception {
        // Arrange
        String body = """
                From: colleague@example.com
                To: bounces@hamkorbank.uz
                Subject: Question
                Content-Type: text/plain; charset=UTF-8

                Is this mailbox monitored?
                """;

        // Act
        Optional<BounceReport> report = codec.parse(message(body));

        // Assert
        assertThat(report).isEmpty();
    }

    @Test
    @DisplayName("EM-02: a report whose X-Comm-Message-Id survived but whose Message-ID was rewritten still binds")
    void bindsByTheHubHeaderWhenTheMessageIdWasRewritten() throws Exception {
        // Arrange
        MessageId messageId = MessageId.newId();
        String body = dsnText(messageId, "failed", "5.1.1")
                .replace("<%s@hamkorbank.uz>".formatted(messageId), "<rewritten-by-a-gateway@example.com>");

        // Act
        BounceReport report = codec.parse(message(body)).orElseThrow();

        // Assert
        assertThat(report.hubMessageIdOptional()).contains(messageId);
    }

    private static MimeMessage dsn(MessageId messageId, String action, String status) throws Exception {
        return message(dsnText(messageId, action, status));
    }

    /** A report in the shape RFC 3464 prescribes and Postfix, Exchange and friends actually send. */
    private static String dsnText(MessageId messageId, String action, String status) {
        return """
                From: MAILER-DAEMON@example.com
                To: bounces@hamkorbank.uz
                Subject: Undelivered Mail Returned to Sender
                MIME-Version: 1.0
                Content-Type: multipart/report; report-type=delivery-status; boundary="BOUNDARY"

                --BOUNDARY
                Content-Type: text/plain; charset=us-ascii

                This is the mail system at host example.com.

                --BOUNDARY
                Content-Type: message/delivery-status

                Reporting-MTA: dns; example.com

                Final-Recipient: rfc822; client@example.com
                Action: %s
                Status: %s
                Diagnostic-Code: smtp; 550 5.1.1 <client@example.com>: Recipient address rejected

                --BOUNDARY
                Content-Type: text/rfc822-headers

                Message-ID: <%s@hamkorbank.uz>
                X-Comm-Message-Id: %s
                Subject: Выписка

                --BOUNDARY--
                """.formatted(action, status, messageId, messageId);
    }

    private static MimeMessage message(String text) throws Exception {
        return new MimeMessage(
                Session.getInstance(new Properties()),
                new ByteArrayInputStream(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
    }
}
