package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.EventExportResult;
import uz.hamkorbank.commhub.application.port.in.command.ExportDeliveryEventsCommand;
import uz.hamkorbank.commhub.application.port.out.AnalyticsPublisherPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent.DeliveryOutcome;
import uz.hamkorbank.commhub.application.port.out.EventExportRepository;
import uz.hamkorbank.commhub.application.port.out.EventExportRepository.ExportCursor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;

/** FR-6.4: the mart feed advances only over what the broker acknowledged. */
class ExportDeliveryEventsServiceTest {

    private static final Instant START = NOW.minusSeconds(3600);

    private EventExportRepository repository;
    private AnalyticsPublisherPort publisher;
    private ExportDeliveryEventsService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventExportRepository.class);
        publisher = mock(AnalyticsPublisherPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(repository.findCursor(ExportDeliveryEventsService.FEED))
                .thenReturn(Optional.of(new ExportCursor(ExportDeliveryEventsService.FEED, START, null, START)));
        service = new ExportDeliveryEventsService(repository, publisher, clock);
    }

    @Test
    @DisplayName("a full page is published and the cursor moves to its last event")
    void publishesAPageAndAdvancesTheCursor() {
        // Arrange
        DeliveryEvent first = event(START.plusSeconds(10));
        DeliveryEvent last = event(START.plusSeconds(20));
        when(repository.findTerminalAfter(any(), any(), anyInt()))
                .thenReturn(List.of(first, last))
                .thenReturn(List.of());

        // Act
        EventExportResult result = service.export(new ExportDeliveryEventsCommand(2, 5));

        // Assert
        verify(publisher).publish(List.of(first, last));
        ArgumentCaptor<ExportCursor> cursor = ArgumentCaptor.forClass(ExportCursor.class);
        verify(repository).saveCursor(cursor.capture());
        assertThat(cursor.getValue().position()).isEqualTo(last.outcome().terminalAt());
        assertThat(cursor.getValue().lastMessageId()).isEqualTo(last.messageId());
        assertThat(result.exported()).isEqualTo(2);
        assertThat(result.exhausted()).isTrue();
    }

    @Test
    @DisplayName("AD-03: a failed publication leaves the cursor where it was, so the page repeats")
    void leavesTheCursorOnAFailedPublication() {
        // Arrange
        when(repository.findTerminalAfter(any(), any(), anyInt())).thenReturn(List.of(event(START.plusSeconds(5))));
        doThrow(new IllegalStateException("broker is down")).when(publisher).publish(any());

        // Act + Assert
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.export(ExportDeliveryEventsCommand.defaults()));
        verify(repository, never()).saveCursor(any());
    }

    @Test
    @DisplayName("a pass stops at maxPages and says the work is not finished")
    void stopsAtTheConfiguredNumberOfPages() {
        // Arrange: every page comes back full, so the backlog never ends within this pass.
        when(repository.findTerminalAfter(any(), any(), anyInt()))
                .thenReturn(List.of(event(START.plusSeconds(1)), event(START.plusSeconds(2))));

        // Act
        EventExportResult result = service.export(new ExportDeliveryEventsCommand(2, 3));

        // Assert
        verify(publisher, times(3)).publish(any());
        assertThat(result.exported()).isEqualTo(6);
        assertThat(result.exhausted()).isFalse();
    }

    @Test
    @DisplayName("a first run starts from now: waking up must not replay every message ever sent")
    void startsFromTheCurrentMomentOnTheFirstRun() {
        // Arrange
        when(repository.findCursor(ExportDeliveryEventsService.FEED)).thenReturn(Optional.empty());
        when(repository.findTerminalAfter(any(), any(), anyInt())).thenReturn(List.of());

        // Act
        EventExportResult result = service.export(ExportDeliveryEventsCommand.defaults());

        // Assert
        verify(repository).saveCursor(any());
        verify(repository).findTerminalAfter(eq(NOW), eq(null), anyInt());
        assertThat(result.exported()).isZero();
    }

    private static DeliveryEvent event(Instant terminalAt) {
        return new DeliveryEvent(
                MessageId.newId(),
                STREAM_ID,
                null,
                TrafficClass.TRANSACTIONAL,
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                new DeliveryOutcome(MessageStatus.DELIVERED, null, 1, null, 1, START, terminalAt),
                false);
    }
}
