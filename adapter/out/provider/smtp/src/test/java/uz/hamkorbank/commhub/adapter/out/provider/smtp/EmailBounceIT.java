package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.port.in.ProcessProviderStatus;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * Bounce processing end to end against a real IMAP server (QA-03, EM-02).
 *
 * <p>The point of running it against a server rather than handing the codec a string is everything around
 * the parse: that the poller finds the unread reports, that it applies them through the same use case a DLR
 * goes through, and that a processed report is not read a second time.
 */
@Tag("integration")
class EmailBounceIT {

    private static final String MAILBOX = "bounces@hamkorbank.uz";

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetup.IMAP.dynamicPort());

    private final List<ProviderStatusCommand> applied = new ArrayList<>();

    private EmailBouncePoller poller;
    private GreenMailUser mailbox;

    @BeforeEach
    void setUp() {
        applied.clear();
        mailbox = GREEN_MAIL.setUser(MAILBOX, "bounces", "secret");
        poller = new EmailBouncePoller(
                properties(), SmtpProperties.defaults(), new BounceCodec(), recordingUseCase(), FixedClock.standard());
    }

    @Test
    @DisplayName("EM-02: a hard bounce marks the message undelivered and suppresses the address")
    void appliesAHardBounce() throws Exception {
        // Arrange
        MessageId messageId = MessageId.newId();
        mailbox.deliver(dsn(messageId, "failed", "5.1.1"));

        // Act
        poller.poll();

        // Assert
        assertThat(applied).hasSize(1);
        ProviderStatusCommand command = applied.getFirst();
        assertThat(command.messageIdOptional()).contains(messageId);
        assertThat(command.status()).isEqualTo(MessageStatus.UNDELIVERED);
        assertThat(command.suppressAsOptional()).contains(SuppressionReason.HARD_BOUNCE);
        assertThat(command.providerStatus()).isEqualTo("5.1.1");
    }

    @Test
    @DisplayName("EM-02: a processed report is not read again on the next pass")
    void doesNotReadTheSameReportTwice() throws Exception {
        // Arrange
        mailbox.deliver(dsn(MessageId.newId(), "failed", "5.1.1"));

        // Act
        poller.poll();
        poller.poll();

        // Assert
        assertThat(applied).hasSize(1);
    }

    @Test
    @DisplayName("EM-02: a delayed report changes nothing — the relay is still trying")
    void ignoresADelay() throws Exception {
        // Arrange
        mailbox.deliver(dsn(MessageId.newId(), "delayed", "4.4.1"));

        // Act
        poller.poll();

        // Assert
        assertThat(applied).isEmpty();
    }

    @Test
    @DisplayName("EM-02: a mailbox-full bounce is undelivered but keeps the customer's address")
    void doesNotSuppressAFullMailbox() throws Exception {
        // Arrange
        mailbox.deliver(dsn(MessageId.newId(), "failed", "5.2.2"));

        // Act
        poller.poll();

        // Assert
        assertThat(applied).hasSize(1);
        assertThat(applied.getFirst().status()).isEqualTo(MessageStatus.UNDELIVERED);
        assertThat(applied.getFirst().suppressAsOptional()).isEmpty();
    }

    @Test
    @DisplayName("EM-02: an ordinary email in the bounce mailbox is left alone, not guessed at")
    void leavesAnOrdinaryEmailAlone() throws Exception {
        // Arrange
        mailbox.deliver(message("""
                From: colleague@example.com
                To: bounces@hamkorbank.uz
                Subject: Question
                Content-Type: text/plain; charset=UTF-8

                Is this mailbox monitored?
                """));

        // Act
        poller.poll();

        // Assert
        assertThat(applied).isEmpty();
    }

    private ProcessProviderStatus recordingUseCase() {
        return command -> {
            applied.add(command);
            return ProcessProviderStatusResult.applied(command.messageId(), command.status());
        };
    }

    private static SmtpBounceProperties properties() {
        return new SmtpBounceProperties(
                true,
                "127.0.0.1",
                GREEN_MAIL.getImap().getPort(),
                false,
                new SmtpBounceProperties.Credentials("bounces", "secret"),
                "INBOX",
                200,
                new SmtpBounceProperties.Settings(null, null, null, false));
    }

    private static MimeMessage dsn(MessageId messageId, String action, String status) throws Exception {
        return message("""
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
                """.formatted(action, status, messageId, messageId));
    }

    private static MimeMessage message(String text) throws Exception {
        return new MimeMessage(
                Session.getInstance(new Properties()),
                new ByteArrayInputStream(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
    }
}
