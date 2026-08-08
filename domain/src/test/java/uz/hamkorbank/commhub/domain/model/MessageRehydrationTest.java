package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.envelope;
import static uz.hamkorbank.commhub.domain.DomainFixtures.msisdn;
import static uz.hamkorbank.commhub.domain.DomainFixtures.uzs;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;

/** Reconstitution of a message read back from storage (§10.1 {@code message}, DB-01). */
class MessageRehydrationTest {

    private static final ProviderRef PLAYMOBILE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("PLAYMOBILE"), Channel.SMS, AdapterType.of("playmobile-http"));

    @Test
    @DisplayName("a rehydrated message carries the stored state instead of replaying the status machine")
    void rehydrationRestoresStoredState() {
        // Arrange
        MessageEnvelope envelope = envelope("abc0000042", TrafficClass.TRANSACTIONAL);
        Instant deliveredAt = NOW.plusSeconds(30);
        List<StatusChange> history = List.of(
                new StatusChange(MessageStatus.ACCEPTED, null, null, Actor.sourceSystem("mobile-app"), null, NOW),
                new StatusChange(MessageStatus.VALIDATED, null, null, Actor.system(), null, NOW.plusSeconds(1)),
                new StatusChange(MessageStatus.ROUTED, null, null, Actor.system(), null, NOW.plusSeconds(2)),
                new StatusChange(
                        MessageStatus.DELIVERED,
                        null,
                        "DELIVRD",
                        Actor.provider("PLAYMOBILE"),
                        ProviderCode.of("PLAYMOBILE"),
                        deliveredAt));

        // Act
        Message message = rehydration(envelope)
                .status(MessageStatus.DELIVERED, null, deliveredAt)
                .route(Channel.SMS, PLAYMOBILE)
                .billing(2, uzs("49.0000"))
                .statusHistory(history)
                .attempts(List.of(acceptedAttempt(envelope.id())))
                .build();

        // Assert
        assertThat(message.id()).isEqualTo(envelope.id());
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(message.terminalAt()).contains(deliveredAt);
        assertThat(message.selectedChannel()).contains(Channel.SMS);
        assertThat(message.selectedProvider()).contains(PLAYMOBILE);
        assertThat(message.segments()).isEqualTo(2);
        assertThat(message.cost()).contains(uzs("49.0000"));
        assertThat(message.statusHistory()).hasSize(4);
        // История восстановлена дословно: провайдерский код второй записи не переписан текущим маршрутом.
        assertThat(message.statusHistory().get(1).providerCodeOptional()).isEmpty();
        assertThat(message.attempts()).hasSize(1);
        assertThat(message.nextAttemptNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("a rehydrated non-terminal message keeps its rejection reason and stays routable")
    void rehydrationKeepsNonTerminalState() {
        // Arrange
        MessageEnvelope envelope = envelope("abc0000043", TrafficClass.NOTIFICATION);

        // Act
        Message message = rehydration(envelope)
                .status(MessageStatus.RETRYING, null, null)
                .route(Channel.SMS, PLAYMOBILE)
                .statusHistory(List.of(new StatusChange(
                        MessageStatus.ACCEPTED, null, null, Actor.sourceSystem("mobile-app"), null, NOW)))
                .test(true)
                .build();

        // Assert
        assertThat(message.terminalAt()).isEmpty();
        assertThat(message.isTest()).isTrue();
        assertThat(message.statusReason()).isEmpty();
        assertThat(message.transitionTo(MessageStatus.SENDING, Actor.system(), NOW.plusSeconds(5)))
                .isNotNull();
    }

    @Test
    @DisplayName("a duplicate keeps the identifier of the original submission (FR-1.5)")
    void rehydrationRestoresDuplicateLink() {
        // Arrange
        MessageEnvelope envelope = envelope("abc0000044", TrafficClass.NOTIFICATION);
        MessageId original = MessageId.newId();

        // Act
        Message message = rehydration(envelope)
                .status(MessageStatus.DUPLICATE, RejectionReason.DUPLICATE_SUBMISSION, NOW)
                .duplicateOf(original)
                .statusHistory(List.of(new StatusChange(
                        MessageStatus.DUPLICATE,
                        RejectionReason.DUPLICATE_SUBMISSION,
                        null,
                        Actor.system(),
                        null,
                        NOW)))
                .build();

        // Assert
        assertThat(message.duplicateOf()).contains(original);
        assertThat(message.statusReason()).contains(RejectionReason.DUPLICATE_SUBMISSION);
    }

    @Test
    @DisplayName("a terminal status without terminalAt is rejected as a corrupted row")
    void rehydrationRejectsTerminalStatusWithoutInstant() {
        // Arrange
        Message.Rehydration corrupted = rehydration(envelope("abc0000045", TrafficClass.NOTIFICATION))
                .status(MessageStatus.FAILED, RejectionReason.ATTEMPTS_EXHAUSTED, null);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(corrupted::build)
                .withMessageContaining("terminalAt");
    }

    @Test
    @DisplayName("an empty status history is rejected: every stored message has at least ACCEPTED")
    void rehydrationRejectsEmptyHistory() {
        // Arrange
        Message.Rehydration corrupted = rehydration(envelope("abc0000046", TrafficClass.NOTIFICATION));

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(corrupted::build)
                .withMessageContaining("statusHistory");
    }

    private static Message.Rehydration rehydration(MessageEnvelope envelope) {
        return Message.rehydrate(
                envelope,
                Recipient.ofMsisdn(msisdn()),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("Hello", "HAMKORBANK")),
                null,
                Timing.immediate(),
                NOW);
    }

    private static DeliveryAttempt acceptedAttempt(MessageId messageId) {
        DeliveryAttempt attempt = DeliveryAttempt.start(
                AttemptId.newId(), messageId, PLAYMOBILE, 1, ProviderMessageId.of("pm-0000000001"), NOW);
        attempt.succeed("0", null, NOW.plusSeconds(1));
        return attempt;
    }
}
