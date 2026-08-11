package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRuntimeSettings;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderSupport;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderThrottle;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * The email adapter against a real SMTP server (QA-03, EM-01).
 *
 * <p>GreenMail rather than a mock, because everything worth testing here is what actually ends up on the
 * wire: the MIME structure, the headers a bounce will be matched by, and the fact that a pooled connection
 * carries a second message.
 */
@Tag("integration")
class SmtpEmailAdapterIT {

    /** Dynamic ports: the local docker stack already holds 3025/3143 (QA-03). */
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetup.SMTP.dynamicPort());

    private Path attachments;
    private SmtpEmailAdapter adapter;
    private ProviderThrottle throttle;

    @BeforeEach
    void setUp(@TempDir Path attachmentDirectory) {
        attachments = attachmentDirectory;
        throttle = new ProviderThrottle();
        adapter = adapter(RateLimit.unlimited(), SmtpProperties.Dkim.disabled());
    }

    @Test
    @DisplayName("EM-01: an accepted message arrives with both bodies, the Hub identifier and the sender")
    void sendsAMultipartMessage() throws Exception {
        // Act
        MessageId messageId = MessageId.newId();
        ProviderAck ack = adapter.submit(EmailSubmissions.multipart(messageId));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(ack.providerMessageId().value()).isEqualTo("%s@hamkorbank.uz".formatted(messageId));
        assertThat(GREEN_MAIL.waitForIncomingEmail(1)).isTrue();
        MimeMessage received = GREEN_MAIL.getReceivedMessages()[0];
        assertThat(received.getHeader(SmtpMessageCodec.MESSAGE_ID_HEADER)).containsExactly(messageId.toString());
        assertThat(received.getHeader("Message-ID")).containsExactly("<%s@hamkorbank.uz>".formatted(messageId));
        assertThat(received.getSubject()).isEqualTo("Выписка");
        assertThat(received.getContentType()).startsWith("multipart/alternative");
        MimeMultipart body = (MimeMultipart) received.getContent();
        assertThat(body.getBodyPart(0).getContentType()).startsWith("text/plain");
        assertThat(body.getBodyPart(1).getContent().toString()).contains("<p>Ваша выписка готова.</p>");
    }

    @Test
    @DisplayName("EM-01: an attachment travels as multipart/mixed with its bytes intact")
    void sendsAnAttachment() throws Exception {
        // Arrange
        byte[] pdf = "%PDF-1.4 statement".getBytes(StandardCharsets.UTF_8);
        Files.write(attachments.resolve("statement.pdf"), pdf);
        adapter = adapter(RateLimit.unlimited(), SmtpProperties.Dkim.disabled());
        Attachment attachment = new Attachment("Выписка.pdf", "application/pdf", pdf.length, "statement.pdf");

        // Act
        ProviderAck ack = adapter.submit(EmailSubmissions.withAttachment(MessageId.newId(), attachment));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(GREEN_MAIL.waitForIncomingEmail(1)).isTrue();
        MimeMultipart body = (MimeMultipart) GREEN_MAIL.getReceivedMessages()[0].getContent();
        assertThat(body.getContentType()).startsWith("multipart/mixed");
        assertThat(body.getBodyPart(1).getFileName()).isEqualTo("Выписка.pdf");
        assertThat(body.getBodyPart(1).getInputStream().readAllBytes()).isEqualTo(pdf);
    }

    @Test
    @DisplayName("EM-01: consecutive messages reuse the pooled connection and all arrive")
    void reusesPooledConnections() {
        // Act
        List<ProviderAck> acks = List.of(
                adapter.submit(EmailSubmissions.textOnly(MessageId.newId())),
                adapter.submit(EmailSubmissions.textOnly(MessageId.newId())),
                adapter.submit(EmailSubmissions.textOnly(MessageId.newId())));

        // Assert
        assertThat(acks).allMatch(ProviderAck::isAccepted);
        assertThat(GREEN_MAIL.waitForIncomingEmail(3)).isTrue();
        assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(3);
    }

    @Test
    @DisplayName("EM-03: a signed message carries a DKIM-Signature of the configured selector")
    void signsWithDkim() throws Exception {
        // Arrange
        adapter = adapter(
                RateLimit.unlimited(),
                new SmtpProperties.Dkim(true, "hamkorbank.uz", "hub", TestKeys.DKIM_PRIVATE_KEY_PEM, null));

        // Act
        ProviderAck ack = adapter.submit(EmailSubmissions.textOnly(MessageId.newId()));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(GREEN_MAIL.waitForIncomingEmail(1)).isTrue();
        String signature = GREEN_MAIL.getReceivedMessages()[0].getHeader(DkimSigner.HEADER)[0];
        assertThat(signature).contains("d=hamkorbank.uz").contains("s=hub").contains("a=rsa-sha256");
    }

    @Test
    @DisplayName("FR-2.5: a message over the relay's ceiling is held back retryably, not sent")
    void throttlesOverTheCeiling() {
        // Arrange — one message per recipient per hour makes the second one hit the ceiling
        adapter = adapter(new RateLimit(0, 0, 1), SmtpProperties.Dkim.disabled());

        // Act
        ProviderAck first = adapter.submit(EmailSubmissions.textOnly(MessageId.newId()));
        ProviderAck second = adapter.submit(EmailSubmissions.textOnly(MessageId.newId()));

        // Assert
        assertThat(first.isAccepted()).isTrue();
        assertThat(second.isAccepted()).isFalse();
        assertThat(second.responseCode()).isEqualTo(SmtpEmailAdapter.THROTTLED_CODE);
        // Retryable, не отказ: придержанное письмо должно уйти резервным маршрутом, а не в DLQ.
        assertThat(second.errorClass()).isEqualTo(ErrorClass.RETRYABLE);
    }

    @Test
    @DisplayName("PR-01: a relay that is not there is a retryable failure, not a rejected message")
    void anUnreachableRelayIsRetryable() {
        // Arrange — a port nothing listens on
        adapter = new SmtpEmailAdapter(
                properties(RateLimit.unlimited(), SmtpProperties.Dkim.disabled(), 1),
                new SmtpMessageCodec(new AttachmentStore(AttachmentStoreProperties.disabled())),
                support());

        // Act
        ProviderAck ack = adapter.submit(EmailSubmissions.textOnly(MessageId.newId()));

        // Assert
        assertThat(ack.isAccepted()).isFalse();
        assertThat(ack.isRetryable()).isTrue();
    }

    private SmtpEmailAdapter adapter(RateLimit rateLimit, SmtpProperties.Dkim dkim) {
        return new SmtpEmailAdapter(
                properties(rateLimit, dkim, GREEN_MAIL.getSmtp().getPort()),
                new SmtpMessageCodec(new AttachmentStore(new AttachmentStoreProperties(attachments.toString(), null))),
                support());
    }

    private ProviderSupport support() {
        return new ProviderSupport(
                new ProviderCallExecutor(
                        CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), FixedClock.standard()),
                throttle,
                ProviderRuntimeSettings.configurationOnly(),
                FixedClock.standard(),
                new ProviderRestClients());
    }

    private static SmtpProperties properties(RateLimit rateLimit, SmtpProperties.Dkim dkim, int port) {
        return new SmtpProperties(
                true,
                "SMTP",
                new SmtpProperties.Server(
                        "127.0.0.1", port, SmtpProperties.Security.NONE, null, null, null, null, null),
                new SmtpProperties.Sending("no-reply@hamkorbank.uz", "Hamkorbank", null, "bounces@hamkorbank.uz"),
                new SmtpProperties.Pool(2, 100, null, null),
                dkim,
                rateLimit,
                null);
    }
}
