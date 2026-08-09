package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** Shape of the MIME message the relay is handed (EM-01). */
class SmtpMessageCodecTest {

    private static final Date SENT_AT = Date.from(FixedInstants.NOW);

    private Session session;
    private SmtpProperties.Sending sending;

    @BeforeEach
    void setUp() {
        session = Session.getInstance(SmtpProperties.defaults().sessionProperties());
        sending = new SmtpProperties.Sending("no-reply@hamkorbank.uz", "Hamkorbank", "help@hamkorbank.uz", null);
    }

    @Test
    @DisplayName("EM-01: the message carries X-Comm-Message-Id and a Message-ID the Hub wrote")
    void carriesTheHubIdentifier() throws Exception {
        // Arrange
        MessageId messageId = MessageId.newId();
        EmailSubmission submission = EmailSubmissions.textOnly(messageId);

        // Act
        MimeMessage message = codec().encode(session, submission, sending, SENT_AT);

        // Assert
        assertThat(message.getHeader(SmtpMessageCodec.MESSAGE_ID_HEADER)).containsExactly(messageId.toString());
        assertThat(message.getHeader("Message-ID")).containsExactly("<%s@hamkorbank.uz>".formatted(messageId));
        assertThat(message.getHeader(SmtpMessageCodec.TRAFFIC_CLASS_HEADER)).containsExactly("TRANSACTIONAL");
        assertThat(message.getFrom()[0].toString()).contains("no-reply@hamkorbank.uz");
        assertThat(message.getReplyTo()[0].toString()).contains("help@hamkorbank.uz");
        assertThat(message.getSubject()).isEqualTo("Выписка");
    }

    @Test
    @DisplayName("EM-01: both bodies become multipart/alternative with the plain text first")
    void bothBodiesBecomeMultipartAlternative() throws Exception {
        // Arrange
        EmailSubmission submission = EmailSubmissions.multipart(MessageId.newId());

        // Act
        MimeMessage message = codec().encode(session, submission, sending, SENT_AT);

        // Assert
        assertThat(message.getContentType()).startsWith("multipart/alternative");
        MimeMultipart body = (MimeMultipart) message.getContent();
        assertThat(body.getCount()).isEqualTo(2);
        assertThat(body.getBodyPart(0).getContentType()).startsWith("text/plain");
        // Последняя альтернатива — предпочтительная (RFC 2046 §5.1.4): HTML обязан быть вторым.
        assertThat(body.getBodyPart(1).getContentType()).startsWith("text/html");
        assertThat(body.getBodyPart(1).getContent()).isEqualTo("<p>Ваша выписка готова.</p>");
    }

    @Test
    @DisplayName("EM-01: one body alone stays a single part")
    void singleBodyStaysSinglePart() throws Exception {
        // Act
        MimeMessage message = codec().encode(session, EmailSubmissions.textOnly(MessageId.newId()), sending, SENT_AT);

        // Assert
        assertThat(message.getContentType()).startsWith("text/plain");
        assertThat(message.getContent()).isEqualTo("Ваша выписка готова.");
    }

    @Test
    @DisplayName("EM-01: an attachment wraps the body in multipart/mixed and keeps its file name")
    void attachmentWrapsTheBody(@TempDir Path attachmentDirectory) throws Exception {
        // Arrange
        Files.write(attachmentDirectory.resolve("statement.pdf"), "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        Attachment attachment = new Attachment("Выписка.pdf", "application/pdf", 8, "statement.pdf");

        // Act
        MimeMessage message = codec(attachmentDirectory)
                .encode(session, EmailSubmissions.withAttachment(MessageId.newId(), attachment), sending, SENT_AT);

        // Assert
        assertThat(message.getContentType()).startsWith("multipart/mixed");
        MimeMultipart body = (MimeMultipart) message.getContent();
        assertThat(body.getCount()).isEqualTo(2);
        assertThat(body.getBodyPart(0).getContentType()).startsWith("text/plain");
        assertThat(body.getBodyPart(1).getFileName()).isEqualTo("Выписка.pdf");
        assertThat(body.getBodyPart(1).getContentType()).startsWith("application/pdf");
    }

    @Test
    @DisplayName("EM-01: an attachment whose bytes cannot be found refuses the message rather than sending it empty")
    void unreadableAttachmentRefusesTheMessage(@TempDir Path attachmentDirectory) {
        // Arrange
        Attachment attachment = new Attachment("missing.pdf", "application/pdf", 8, "missing.pdf");
        EmailSubmission submission = EmailSubmissions.withAttachment(MessageId.newId(), attachment);

        // Act + Assert
        assertThatExceptionOfType(AttachmentStore.AttachmentNotAvailableException.class)
                .isThrownBy(() -> codec(attachmentDirectory).encode(session, submission, sending, SENT_AT));
    }

    @Test
    @DisplayName("EM-02: a Message-ID the Hub wrote is read back as the message identifier")
    void messageIdRoundTrips() {
        // Arrange
        MessageId messageId = MessageId.newId();

        // Act + Assert
        assertThat(SmtpMessageCodec.messageIdFrom("<%s@hamkorbank.uz>".formatted(messageId)))
                .contains(messageId);
        assertThat(SmtpMessageCodec.messageIdFrom("<someone-elses-id@example.com>"))
                .isEmpty();
        assertThat(SmtpMessageCodec.messageIdFrom(null)).isEmpty();
    }

    private SmtpMessageCodec codec() {
        return new SmtpMessageCodec(new AttachmentStore(AttachmentStoreProperties.disabled()));
    }

    private static SmtpMessageCodec codec(Path directory) {
        return new SmtpMessageCodec(new AttachmentStore(new AttachmentStoreProperties(directory.toString(), null)));
    }
}
