package uz.hamkorbank.commhub.application.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.androidToken;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.msisdn;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.policy.EmailPolicy;
import uz.hamkorbank.commhub.application.policy.PanPolicy;
import uz.hamkorbank.commhub.application.policy.PushPolicy;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
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

    /**
     * FR-1.4, and the reason the routing case for the same input was retired (D-9).
     *
     * <p>{@code Router} carries a branch of its own for an unreachable recipient ("recipient has no
     * usable address for the planned channels"), and it asks the aggregate exactly what this stage
     * asks — {@code deliverableChannels().isEmpty()}. Validation runs first, so on the message path
     * the routing branch is unreachable and this is the only answer a source system ever sees. It
     * matters which one wins: this reason renders as {@code 400} ("fix the request"), the routing one
     * as {@code 503} ("retry later") — and a retry never helps, because the document will not change.
     */
    @Test
    @DisplayName("FR-1.4: a recipient with no address for the planned channel is refused before routing")
    void rejectsRecipientWithoutAddressForThePlannedChannel() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.rejecting());

        // Act
        PipelineVerdict verdict = validator.validate(unreachableRecipient());

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.VALIDATION_FAILED);
        assertThat(verdict.detail()).contains("no address for the planned channels");
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

    @Test
    @DisplayName("PU-11: a payload over the 4 KiB platform limit is refused before any device is called")
    void rejectsOversizedPushPayload() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.rejecting());
        Message message = pushMessage(
                new PushContent("Hamkorbank", "x".repeat(PushContent.MAX_PAYLOAD_BYTES), Map.of(), null, null),
                androidToken("device-a"));

        // Act
        PipelineVerdict verdict = validator.validate(message);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.VALIDATION_FAILED);
        assertThat(verdict.detail()).contains("PU-11");
    }

    @Test
    @DisplayName("PU-09: a submission addressing more devices than the fan-out allows is a rejection, not 200 calls")
    void rejectsTooManyDevices() {
        // Arrange — a source system broadcasting through the single-message endpoint
        MessageValidator validator = new MessageValidator(
                new PanDetector(), PanPolicy.rejecting(), EmailPolicy.defaults(), new PushPolicy(4096, 2), metrics);
        Message message = pushMessage(
                PushContent.of("Hamkorbank", "Hisobingiz to'ldirildi"),
                androidToken("a"),
                androidToken("b"),
                androidToken("c"));

        // Act
        PipelineVerdict verdict = validator.validate(message);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.detail()).contains("PU-09");
    }

    @Test
    @DisplayName("EM-01: an email over the attachment ceiling is refused before it reaches a relay")
    void rejectsOversizedAttachments() {
        // Arrange — the total is what a relay enforces, and the message names which ceiling it broke
        MessageValidator validator = new MessageValidator(
                new PanDetector(),
                PanPolicy.rejecting(),
                new EmailPolicy(5, 1024, 1536),
                PushPolicy.defaults(),
                metrics);
        Message message = emailWithAttachments(
                new Attachment("a.pdf", "application/pdf", 1000, "a"),
                new Attachment("b.pdf", "application/pdf", 1000, "b"));

        // Act
        PipelineVerdict verdict = validator.validate(message);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.VALIDATION_FAILED);
        assertThat(verdict.detail()).contains("attachments total").contains("EM-01");
    }

    @Test
    @DisplayName("EM-01: one file over the per-file ceiling names the file, so the sender knows what to drop")
    void namesTheOversizedFile() {
        // Arrange
        MessageValidator validator = new MessageValidator(
                new PanDetector(),
                PanPolicy.rejecting(),
                new EmailPolicy(5, 512, 100_000),
                PushPolicy.defaults(),
                metrics);

        // Act
        PipelineVerdict verdict =
                validator.validate(emailWithAttachments(new Attachment("Выписка.pdf", "application/pdf", 4096, "a")));

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.detail()).contains("Выписка.pdf");
    }

    @Test
    @DisplayName("EM-01: attachments within the limits pass")
    void passesAttachmentsWithinTheLimits() {
        // Arrange
        MessageValidator validator = validator(PanPolicy.rejecting());

        // Act
        PipelineVerdict verdict =
                validator.validate(emailWithAttachments(new Attachment("a.pdf", "application/pdf", 4096, "a")));

        // Assert
        assertThat(verdict.isRejected()).isFalse();
    }

    /** SMS content for a recipient who only has an email address: no channel is deliverable. */
    private static Message unreachableRecipient() {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("noaddr00001"), TrafficClass.TRANSACTIONAL),
                new Recipient(null, null, EmailAddress.of("client@example.uz"), List.of()),
                SmsContent.of("Ваш код: 1234"),
                NOW);
    }

    private static Message pushMessage(PushContent content, PushToken... tokens) {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("abc0000001"), TrafficClass.NOTIFICATION),
                new Recipient(null, null, null, List.of(tokens)),
                content,
                NOW);
    }

    private MessageValidator validator(PanPolicy policy) {
        return new MessageValidator(new PanDetector(), policy, EmailPolicy.defaults(), PushPolicy.defaults(), metrics);
    }

    private static Message emailWithAttachments(Attachment... attachments) {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("att0000001"), TrafficClass.TRANSACTIONAL),
                new Recipient(null, null, EmailAddress.of("client@example.uz"), List.of()),
                new EmailContent("Выписка", null, "Во вложении.", List.of(attachments), null),
                NOW);
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
