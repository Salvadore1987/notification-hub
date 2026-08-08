package uz.hamkorbank.commhub.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.androidToken;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushMessage;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushRecipient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxEventType;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/** Retiring a device token the platform declared dead (PU-04, PU-08). */
class PushTokenRegistrarTest {

    private static final Provider FCM = pushProvider("FCM", "fcm-http");
    private static final PushToken TOKEN = androidToken("device-a");

    private SuppressionRepository suppressions;
    private OutboxPort outbox;
    private MetricsPort metrics;
    private PushTokenRegistrar registrar;

    @BeforeEach
    void setUp() {
        suppressions = mock(SuppressionRepository.class);
        outbox = mock(OutboxPort.class);
        metrics = mock(MetricsPort.class);
        registrar = new PushTokenRegistrar(suppressions, outbox, metrics);
    }

    @Test
    @DisplayName("PU-04: a dead token is suppressed under its hash and announced to the source system")
    void suppressesAndAnnounces() {
        // Arrange
        when(suppressions.saveIfAbsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Message message = pushMessage(pushRecipient(TOKEN));

        // Act
        boolean retired = registrar.invalidate(message, FCM.ref(), TOKEN, "UNREGISTERED", NOW);

        // Assert
        assertThat(retired).isTrue();
        ArgumentCaptor<SuppressionEntry> entry = ArgumentCaptor.forClass(SuppressionEntry.class);
        verify(suppressions).saveIfAbsent(entry.capture());
        assertThat(entry.getValue().channel()).contains(Channel.PUSH);
        assertThat(entry.getValue().reason()).isEqualTo(SuppressionReason.PUSH_TOKEN_INVALID);
        assertThat(entry.getValue().addressHash()).contains(RecipientAddresses.of(TOKEN));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().type()).isEqualTo(OutboxEventType.PUSH_TOKEN_INVALIDATED);
        PushTokenInvalidatedEvent payload =
                (PushTokenInvalidatedEvent) event.getValue().payload();
        assertThat(payload.token()).isEqualTo(TOKEN);
        assertThat(payload.reason()).isEqualTo("UNREGISTERED");
        assertThat(payload.provider()).isEqualTo(FCM.code());
        verify(metrics).recipientSuppressed(Channel.PUSH, SuppressionReason.PUSH_TOKEN_INVALID);
    }

    @Test
    @DisplayName("PU-04: a token already retired produces no second event — a campaign would repeat it per message")
    void isIdempotent() {
        // Arrange — saveIfAbsent answers with the entry that was already in force
        when(suppressions.saveIfAbsent(any()))
                .thenAnswer(invocation -> SuppressionEntry.forAddress(
                        SuppressionEntryId.newId(),
                        Channel.PUSH,
                        RecipientAddresses.of(TOKEN),
                        SuppressionReason.PUSH_TOKEN_INVALID,
                        NOW,
                        "provider:FCM"));
        Message message = pushMessage(pushRecipient(TOKEN));

        // Act
        boolean retired = registrar.invalidate(message, FCM.ref(), TOKEN, "UNREGISTERED", NOW);

        // Assert
        assertThat(retired).isFalse();
        verify(outbox, never()).append(any());
        verify(metrics, never()).recipientSuppressed(any(), any());
    }

    @Test
    @DisplayName("PU-04: the fan-out asks per token whether it may still be written to")
    void reportsARetiredToken() {
        // Arrange
        when(suppressions.findActiveByAddress(RecipientAddresses.of(TOKEN), Channel.PUSH, NOW))
                .thenReturn(java.util.Optional.of(SuppressionEntry.forAddress(
                        SuppressionEntryId.newId(),
                        Channel.PUSH,
                        RecipientAddresses.of(TOKEN),
                        SuppressionReason.PUSH_TOKEN_INVALID,
                        NOW,
                        "provider:FCM")));

        // Act & Assert
        assertThat(registrar.isRetired(TOKEN, NOW)).isTrue();
        assertThat(registrar.isRetired(androidToken("another"), NOW)).isFalse();
    }
}
