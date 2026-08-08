package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.dto.OutboxRelayResult;
import uz.hamkorbank.commhub.application.port.in.command.PublishOutboxEventsCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.OutboxEventType;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.PendingOutboxEvent;
import uz.hamkorbank.commhub.application.port.out.StatusPublisherPort;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** The relay half of the transactional outbox: publish, then mark — never the other way round (AD-03). */
@ExtendWith(MockitoExtension.class)
class PublishOutboxEventsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");

    @Mock
    private OutboxPort outbox;

    @Mock
    private StatusPublisherPort publisher;

    @Mock
    private ClockPort clock;

    @InjectMocks
    private PublishOutboxEventsService service;

    @Test
    @DisplayName("each claimed event is published and then marked published")
    void publishesAndMarksTheBatch() {
        // Arrange
        PendingOutboxEvent status = pending(OutboxEventType.MESSAGE_STATUS);
        PendingOutboxEvent dlq = pending(OutboxEventType.MESSAGE_DLQ);
        when(outbox.pollUnpublished(anyInt())).thenReturn(List.of(status, dlq));
        when(clock.now()).thenReturn(NOW);

        // Act
        OutboxRelayResult result = service.publish(PublishOutboxEventsCommand.defaults());

        // Assert
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.more()).isFalse();
        verify(publisher).publishStatus(status.payload());
        verify(publisher).publishDlq(dlq.payload());
        verify(outbox).markPublished(status, NOW);
        verify(outbox).markPublished(dlq, NOW);
    }

    @Test
    @DisplayName("an event is never marked published when the broker refused it (AD-03)")
    void leavesAFailedEventUnpublished() {
        // Arrange
        PendingOutboxEvent event = pending(OutboxEventType.MESSAGE_STATUS);
        when(outbox.pollUnpublished(anyInt())).thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker is down")).when(publisher).publishStatus(event.payload());

        // Act
        OutboxRelayResult result = service.publish(PublishOutboxEventsCommand.defaults());

        // Assert
        assertThat(result.published()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        verify(outbox).markFailed(event, "IllegalStateException: broker is down");
        verify(outbox, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("a failure stops the pass so statuses of one message cannot overtake each other")
    void stopsThePassOnTheFirstFailure() {
        // Arrange
        PendingOutboxEvent first = pending(OutboxEventType.MESSAGE_STATUS);
        PendingOutboxEvent second = pending(OutboxEventType.MESSAGE_STATUS);
        when(outbox.pollUnpublished(anyInt())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broker is down")).when(publisher).publishStatus(first.payload());

        // Act
        OutboxRelayResult result = service.publish(PublishOutboxEventsCommand.defaults());

        // Assert
        assertThat(result.more()).isFalse();
        verify(publisher, never()).publishStatus(second.payload());
        verify(outbox, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("a full batch asks the scheduler for another pass at once")
    void reportsMoreWhenTheBatchWasFull() {
        // Arrange
        PublishOutboxEventsCommand command = new PublishOutboxEventsCommand(2);
        when(outbox.pollUnpublished(2))
                .thenReturn(List.of(pending(OutboxEventType.MESSAGE_STATUS), pending(OutboxEventType.MESSAGE_STATUS)));
        when(clock.now()).thenReturn(NOW);

        // Act
        OutboxRelayResult result = service.publish(command);

        // Assert
        assertThat(result.more()).isTrue();
    }

    @Test
    @DisplayName("an empty outbox touches neither the broker nor the store")
    void doesNothingWhenTheOutboxIsEmpty() {
        // Arrange
        when(outbox.pollUnpublished(anyInt())).thenReturn(List.of());

        // Act
        OutboxRelayResult result = service.publish(PublishOutboxEventsCommand.defaults());

        // Assert
        assertThat(result.isIdle()).isTrue();
        verify(outbox).pollUnpublished(eq(PublishOutboxEventsCommand.DEFAULT_LIMIT));
        verifyNoMoreInteractions(outbox);
        verifyNoMoreInteractions(publisher);
    }

    private static PendingOutboxEvent pending(OutboxEventType type) {
        MessageStatusEvent payload = statusEvent();
        return new PendingOutboxEvent(
                UuidV7.generate(),
                NOW,
                type,
                "message",
                payload.key().messageId().value().toString(),
                payload,
                0);
    }

    private static MessageStatusEvent statusEvent() {
        return new MessageStatusEvent(
                UuidV7.generate(),
                NOW,
                new MessageKey(
                        StreamId.of("mobile-app"),
                        null,
                        MessageId.of(UUID.randomUUID()),
                        ExternalMessageId.of("abc0000001"),
                        CorrelationId.of("corr-1")),
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                MessageStatus.DELIVERED,
                "DLVRD",
                null,
                2);
    }
}
