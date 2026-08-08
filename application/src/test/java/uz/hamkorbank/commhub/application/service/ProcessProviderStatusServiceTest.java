package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.msisdn;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsMessage;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.mapper.MessageMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.application.service.support.SuppressionRegistrar;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;

/** Provider delivery reports and their idempotency (AD-06, ST-01, ST-03, PM-02, SG-02). */
class ProcessProviderStatusServiceTest {

    private static final ProviderMessageId PROVIDER_MESSAGE_ID = ProviderMessageId.of("HB0000000001");

    private MessageRepository messages;
    private OutboxPort outbox;
    private Provider provider;
    private SuppressionRepository suppressions;
    private ProcessProviderStatusService service;

    @BeforeEach
    void setUp() {
        messages = mock(MessageRepository.class);
        outbox = mock(OutboxPort.class);
        provider = smsProvider("PLAYMOBILE");
        when(messages.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
        suppressions = mock(SuppressionRepository.class);
        when(suppressions.saveIfAbsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ProcessProviderStatusService(
                messages,
                new MessageStatusNotifier(outbox, mock(MetricsPort.class), new MessageMapperImpl()),
                new SuppressionRegistrar(suppressions, mock(MetricsPort.class)));
    }

    @Test
    @DisplayName("ST-03: a DLR maps onto DELIVERED and is written to the history with its raw status")
    void appliesDeliveryReport() {
        // Arrange
        Message message = sentMessage();

        // Act
        ProcessProviderStatusResult result = service.process(command(MessageStatus.DELIVERED, "DLVRD"));

        // Assert
        assertThat(result.applied()).isTrue();
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(message.statusHistory().getLast().details()).isEqualTo("DLVRD");
        verify(outbox).append(any());
    }

    @Test
    @DisplayName("PM-02: a repeated callback changes nothing and is reported as ignored")
    void isIdempotentForRepeatedReports() {
        // Arrange
        sentMessage();
        service.process(command(MessageStatus.DELIVERED, "DLVRD"));

        // Act
        ProcessProviderStatusResult result = service.process(command(MessageStatus.DELIVERED, "DLVRD"));

        // Assert
        assertThat(result.applied()).isFalse();
        assertThat(result.detail()).isEqualTo("status already recorded");
    }

    @Test
    @DisplayName("ST-02: a report that would break the status machine is ignored, not applied")
    void ignoresIllegalTransition() {
        // Arrange — the message is already delivered
        Message message = sentMessage();
        service.process(command(MessageStatus.DELIVERED, "DLVRD"));

        // Act
        ProcessProviderStatusResult result = service.process(command(MessageStatus.UNDELIVERED, "EXPIRD"));

        // Assert
        assertThat(result.applied()).isFalse();
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    @DisplayName("SG-03: a report for an unknown message is answered without touching anything")
    void ignoresUnknownMessage() {
        // Arrange
        when(messages.findByProviderMessageId(any(), any())).thenReturn(Optional.empty());

        // Act
        ProcessProviderStatusResult result = service.process(command(MessageStatus.DELIVERED, "DLVRD"));

        // Assert
        assertThat(result.applied()).isFalse();
        assertThat(result.messageIdOptional()).isEmpty();
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("§18.2: a non-delivery report ends the message as UNDELIVERED")
    void appliesNonDelivery() {
        // Arrange
        Message message = sentMessage();

        // Act
        ProcessProviderStatusResult result = service.process(command(MessageStatus.UNDELIVERED, "Fail"));

        // Assert
        assertThat(result.applied()).isTrue();
        assertThat(message.status()).isEqualTo(MessageStatus.UNDELIVERED);
    }

    @Test
    @DisplayName("§18.2 code 7, EM-02: a report that condemns the address also suppresses it")
    void suppressesAddressReportedAsUnusable() {
        // Arrange
        sentMessage();

        // Act
        ProcessProviderStatusResult result = service.process(
                command(MessageStatus.UNDELIVERED, "InBlackList").suppressing(SuppressionReason.PROVIDER_BLACKLIST));

        // Assert
        assertThat(result.applied()).isTrue();
        ArgumentCaptor<SuppressionEntry> entry = ArgumentCaptor.forClass(SuppressionEntry.class);
        verify(suppressions).saveIfAbsent(entry.capture());
        assertThat(entry.getValue().addressHash()).contains(AddressHash.ofMsisdn(msisdn()));
        assertThat(entry.getValue().reason()).isEqualTo(SuppressionReason.PROVIDER_BLACKLIST);
    }

    @Test
    @DisplayName("FR-5.1: the address is suppressed even when the report changes no status")
    void suppressesAddressEvenWhenStatusIsUnchanged() {
        // Arrange: сообщение уже DELIVERED, отчёт о чёрном списке приходит после него.
        Message message = sentMessage();
        message.markDelivered("DLVRD", Actor.provider("PLAYMOBILE"), NOW);

        // Act
        ProcessProviderStatusResult result = service.process(
                command(MessageStatus.UNDELIVERED, "InBlackList").suppressing(SuppressionReason.PROVIDER_BLACKLIST));

        // Assert
        assertThat(result.applied()).isFalse();
        verify(suppressions).saveIfAbsent(any());
    }

    @Test
    @DisplayName("PM-02: an ordinary report suppresses nothing")
    void leavesAddressAloneForOrdinaryReports() {
        // Arrange
        sentMessage();

        // Act
        service.process(command(MessageStatus.DELIVERED, "DLVRD"));

        // Assert
        verify(suppressions, never()).saveIfAbsent(any());
    }

    /** Message already handed to the provider and waiting for its delivery report. */
    private Message sentMessage() {
        Message message = smsMessage();
        message.markValidated(Actor.system(), NOW);
        message.markRouted(Channel.SMS, provider.ref(), Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        message.markSending(Actor.system(), NOW);
        message.markSentToProvider("0", Actor.provider("PLAYMOBILE"), NOW);
        when(messages.findByProviderMessageId(provider.code(), PROVIDER_MESSAGE_ID))
                .thenReturn(Optional.of(message));
        return message;
    }

    private ProviderStatusCommand command(MessageStatus status, String providerStatus) {
        return ProviderStatusCommand.of(provider.code(), PROVIDER_MESSAGE_ID, status, providerStatus, NOW);
    }
}
