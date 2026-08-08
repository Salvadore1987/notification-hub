package uz.hamkorbank.commhub.application.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.msisdn;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.policy.PanPolicy;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;

/** Content validation and the two PAN modes of SEC-05. */
class MessageValidatorTest {

    /** Test PAN that passes Luhn (ISO/IEC 7812-1). */
    private static final String PAN = "4111 1111 1111 1111";

    private MetricsPort metrics;

    @BeforeEach
    void setUp() {
        metrics = mock(MetricsPort.class);
    }

    @Test
    @DisplayName("SEC-05: a card number in an SMS is rejected with PAN_DETECTED")
    void rejectsPanInSms() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.rejecting());

        // Act
        PipelineVerdict verdict = validator.validate(smsMessage("Karta: " + PAN));

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.PAN_DETECTED);
        verify(metrics).panDetected(Channel.SMS, true);
    }

    @Test
    @DisplayName("SEC-05: alert-only mode still rejects an SMS — a PAN in an SMS is banned outright")
    void alwaysRejectsPanInSmsEvenInAlertMode() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.alerting());

        // Act
        PipelineVerdict verdict = validator.validate(smsMessage("Karta: " + PAN));

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        verify(metrics).panDetected(Channel.SMS, true);
    }

    @Test
    @DisplayName("SEC-05: in alert-only mode an email carrying a PAN is counted and let through")
    void countsPanInEmailWithoutBlocking() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.alerting());

        // Act
        PipelineVerdict verdict = validator.validate(emailMessage("Karta " + PAN));

        // Assert
        assertThat(verdict.isRejected()).isFalse();
        verify(metrics).panDetected(Channel.EMAIL, false);
    }

    @Test
    @DisplayName("SEC-05: in the default mode the same email is rejected")
    void rejectsPanInEmailWhenBlocking() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.rejecting());

        // Act
        PipelineVerdict verdict = validator.validate(emailMessage("Karta " + PAN));

        // Assert
        assertThat(verdict.reason()).isEqualTo(RejectionReason.PAN_DETECTED);
        verify(metrics).panDetected(Channel.EMAIL, true);
    }

    @Test
    @DisplayName("SEC-05: an OTP code and an amount are not card numbers")
    void passesOrdinaryContent() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.rejecting());

        // Act
        PipelineVerdict verdict = validator.validate(smsMessage("Kod: 123456. Summa 1 250 000 UZS"));

        // Assert
        assertThat(verdict.isRejected()).isFalse();
        verify(metrics, never()).panDetected(any(), anyBoolean());
    }

    private MessageValidator validator(PanPolicy policy) {
        return new MessageValidator(new PanDetector(), policy, metrics);
    }

    private static Message smsMessage(String text) {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("pan0000001"), TrafficClass.TRANSACTIONAL),
                Recipient.ofMsisdn(msisdn()),
                SmsContent.of(text, "HAMKORBANK"),
                NOW);
    }

    private static Message emailMessage(String body) {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("pan0000002"), TrafficClass.TRANSACTIONAL),
                new Recipient(null, null, EmailAddress.of("client@example.uz"), List.of()),
                EmailContent.ofText("Bank", body),
                NOW);
    }
}
