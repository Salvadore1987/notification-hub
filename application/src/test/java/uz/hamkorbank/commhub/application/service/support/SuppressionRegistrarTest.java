package uz.hamkorbank.commhub.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.msisdn;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsMessage;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/** Automatic suppression on the strength of a provider's own report (FR-5.1, EM-02, §18.2). */
class SuppressionRegistrarTest {

    private static final Actor PROVIDER = Actor.provider("SMSGATE");

    private SuppressionRepository suppressions;
    private MetricsPort metrics;
    private SuppressionRegistrar registrar;

    @BeforeEach
    void setUp() {
        suppressions = mock(SuppressionRepository.class);
        metrics = mock(MetricsPort.class);
        when(suppressions.saveIfAbsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        registrar = new SuppressionRegistrar(suppressions, metrics);
    }

    @Test
    @DisplayName("§18.2: a blacklisted number is listed by hash, with the provider as its author")
    void suppressesReportedAddress() {
        // Arrange
        Message message = smsMessage();

        // Act
        SuppressionEntry entry = registrar
                .suppress(message, Channel.SMS, SuppressionReason.PROVIDER_BLACKLIST, PROVIDER, NOW)
                .orElseThrow();

        // Assert
        assertThat(entry.addressHash()).contains(AddressHash.ofMsisdn(msisdn()));
        assertThat(entry.channel()).contains(Channel.SMS);
        assertThat(entry.reason()).isEqualTo(SuppressionReason.PROVIDER_BLACKLIST);
        assertThat(entry.createdBy()).contains("PROVIDER:SMSGATE");
        verify(metrics).recipientSuppressed(Channel.SMS, SuppressionReason.PROVIDER_BLACKLIST);
    }

    @Test
    @DisplayName("EM-02: a hard bounce that repeats resolves to the entry already in force and alerts once")
    void isIdempotentForRepeatedReports() {
        // Arrange
        SuppressionEntry existing = SuppressionEntry.forAddress(
                SuppressionEntryId.newId(),
                Channel.SMS,
                AddressHash.ofMsisdn(msisdn()),
                SuppressionReason.HARD_BOUNCE,
                NOW,
                "PROVIDER:SMSGATE");
        when(suppressions.saveIfAbsent(any())).thenReturn(existing);

        // Act
        SuppressionEntry entry = registrar
                .suppress(smsMessage(), Channel.SMS, SuppressionReason.HARD_BOUNCE, PROVIDER, NOW)
                .orElseThrow();

        // Assert
        assertThat(entry.id()).isEqualTo(existing.id());
        verify(metrics, never()).recipientSuppressed(any(), any());
    }

    @Test
    @DisplayName("FR-5.1: the channel of the send is taken from the route when the caller does not name it")
    void takesChannelFromTheRoute() {
        // Arrange
        Message message = smsMessage();
        message.assignRoute(Channel.SMS, smsProvider("SMSGATE").ref());

        // Act
        registrar.suppress(message, SuppressionReason.PROVIDER_BLACKLIST, PROVIDER, NOW);

        // Assert
        ArgumentCaptor<SuppressionEntry> captor = ArgumentCaptor.forClass(SuppressionEntry.class);
        verify(suppressions).saveIfAbsent(captor.capture());
        assertThat(captor.getValue().channel()).contains(Channel.SMS);
    }

    @Test
    @DisplayName("FR-5.1: a recipient with no address on the channel has nothing to suppress")
    void skipsRecipientWithoutAddress() {
        // Arrange: сообщение адресовано клиенту, push-токена в получателе нет.
        Message message = Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("push000001"), TrafficClass.NOTIFICATION),
                new Recipient(ClientId.of("CL-42"), msisdn(), null, List.of()),
                PushContent.of("Bank", "Xabar"),
                NOW);

        // Act
        boolean suppressed = registrar
                .suppress(message, Channel.PUSH, SuppressionReason.PROVIDER_BLACKLIST, PROVIDER, NOW)
                .isPresent();

        // Assert
        assertThat(suppressed).isFalse();
        verify(suppressions, never()).saveIfAbsent(any());
    }
}
