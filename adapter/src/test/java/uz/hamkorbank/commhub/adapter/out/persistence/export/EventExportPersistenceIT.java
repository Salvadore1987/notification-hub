package uz.hamkorbank.commhub.adapter.out.persistence.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.adapter.out.persistence.messaging.MessagePersistenceAdapter;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent;
import uz.hamkorbank.commhub.application.port.out.EventExportRepository.ExportCursor;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * The data-mart feed reads finished sends in a stable order and remembers where it stopped (FR-6.4).
 *
 * <p>The ordering is the point of the test: messages become terminal in bunches, and a cursor on the
 * timestamp alone would either skip the ones sharing a microsecond or repeat them forever.
 */
class EventExportPersistenceIT extends AbstractPersistenceIT {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-08T09:00:00Z");
    private static final Instant TERMINAL_AT = ACCEPTED_AT.plusSeconds(30);
    private static final Currency UZS = Currency.getInstance("UZS");
    private static final ProviderRef PLAYMOBILE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("PLAYMOBILE"), Channel.SMS, AdapterType.of("playmobile-http"));

    private final MessagePersistenceAdapter messages;
    private final EventExportPersistenceAdapter export;

    EventExportPersistenceIT(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            MessagePersistenceAdapter messages,
            EventExportPersistenceAdapter export) {
        super(jdbcClient, transactionTemplate);
        this.messages = messages;
        this.export = export;
    }

    @BeforeEach
    void clearMessages() {
        truncate("delivery_attempt", "message_status_history", "message", "export_cursor");
    }

    @Test
    @DisplayName("a delivered message is exported flat, with its cost, segments and attempt count")
    void exportsFinishedSends() {
        // Arrange
        Message message = delivered("evt-0000001", TERMINAL_AT);

        // Act
        List<DeliveryEvent> events = export.findTerminalAfter(ACCEPTED_AT, null, 10);

        // Assert
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.messageId()).isEqualTo(message.id());
            assertThat(event.streamId()).isEqualTo(StreamId.of("mobile-app"));
            assertThat(event.trafficClass()).isEqualTo(TrafficClass.TRANSACTIONAL);
            assertThat(event.channel()).isEqualTo(Channel.SMS);
            assertThat(event.provider()).isEqualTo(ProviderCode.of("PLAYMOBILE"));
            assertThat(event.outcome().status()).isEqualTo(MessageStatus.DELIVERED);
            assertThat(event.outcome().segments()).isEqualTo(2);
            assertThat(event.outcome().cost().amount()).isEqualByComparingTo("241.0000");
            assertThat(event.outcome().attempts()).isEqualTo(1);
            assertThat(event.outcome().terminalAt()).isEqualTo(TERMINAL_AT);
            assertThat(event.test()).isFalse();
        });
    }

    @Test
    @DisplayName("messages still in flight are not exported: the unit of the feed is a finished send")
    void ignoresMessagesInFlight() {
        // Arrange
        messages.save(accepted("evt-0000002"));

        // Act + Assert
        assertThat(export.findTerminalAfter(ACCEPTED_AT.minusSeconds(60), null, 10))
                .isEmpty();
    }

    @Test
    @DisplayName("a rejection is exported too, with its canonical reason and without a provider")
    void exportsRejections() {
        // Arrange
        Message message = accepted("evt-0000003");
        message.reject(RejectionReason.SUPPRESSED, "recipient is suppressed", Actor.system(), TERMINAL_AT);
        messages.save(message);

        // Act
        List<DeliveryEvent> events = export.findTerminalAfter(ACCEPTED_AT, null, 10);

        // Assert
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome().status()).isEqualTo(MessageStatus.REJECTED);
            assertThat(event.outcome().reason()).isEqualTo(RejectionReason.SUPPRESSED);
            assertThat(event.provider()).isNull();
            assertThat(event.channel()).isNull();
        });
    }

    @Test
    @DisplayName("the cursor pages by (terminalAt, id), so messages finishing together are read exactly once")
    void pagesByTimestampAndId() {
        // Arrange — three messages terminal at the very same instant.
        delivered("evt-0000004", TERMINAL_AT);
        delivered("evt-0000005", TERMINAL_AT);
        delivered("evt-0000006", TERMINAL_AT);

        // Act — read them one page at a time, exactly as the exporter does.
        List<DeliveryEvent> first = export.findTerminalAfter(ACCEPTED_AT, null, 2);
        List<DeliveryEvent> second = export.findTerminalAfter(
                first.getLast().outcome().terminalAt(), first.getLast().messageId(), 2);
        List<DeliveryEvent> third = export.findTerminalAfter(
                second.getLast().outcome().terminalAt(), second.getLast().messageId(), 2);

        // Assert
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(third).isEmpty();
        assertThat(first)
                .extracting(DeliveryEvent::messageId)
                .doesNotContainAnyElementsOf(
                        second.stream().map(DeliveryEvent::messageId).toList());
    }

    @Test
    @DisplayName("the cursor survives a restart and is updated in place")
    void storesAndUpdatesTheCursor() {
        // Arrange
        Message message = delivered("evt-0000007", TERMINAL_AT);
        ExportCursor initial = ExportCursor.initial("data-mart", ACCEPTED_AT, ACCEPTED_AT);

        // Act
        export.saveCursor(initial);
        export.saveCursor(new ExportCursor("data-mart", TERMINAL_AT, message.id(), TERMINAL_AT));
        Optional<ExportCursor> stored = export.findCursor("data-mart");

        // Assert
        assertThat(stored).isPresent();
        assertThat(stored.get().position()).isEqualTo(TERMINAL_AT);
        assertThat(stored.get().lastMessageId()).isEqualTo(message.id());
        assertThat(jdbc().sql("SELECT count(*) FROM export_cursor")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(export.findCursor("unknown-feed")).isEmpty();
    }

    private Message delivered(String externalId, Instant terminalAt) {
        Message message = accepted(externalId);
        message.markValidated(Actor.system(), ACCEPTED_AT.plusSeconds(1));
        message.markRouted(Channel.SMS, PLAYMOBILE, Actor.system(), ACCEPTED_AT.plusSeconds(2));
        message.applySegments(2);
        message.applyCost(Money.of(new BigDecimal("241.0000"), UZS));
        message.markQueued(Actor.system(), ACCEPTED_AT.plusSeconds(3));
        message.markSending(Actor.system(), ACCEPTED_AT.plusSeconds(4));
        message.startAttempt(ProviderMessageId.of("pm-" + externalId), ACCEPTED_AT.plusSeconds(4))
                .succeed("200", ProviderMessageId.of("pm-" + externalId), ACCEPTED_AT.plusSeconds(5));
        message.markSentToProvider("ACCEPTD", Actor.provider("PLAYMOBILE"), ACCEPTED_AT.plusSeconds(5));
        message.markDelivered("DLVRD", Actor.provider("PLAYMOBILE"), terminalAt);
        return messages.save(message);
    }

    private static Message accepted(String externalId) {
        return Message.accept(
                MessageEnvelope.single(
                        StreamId.of("mobile-app"), ExternalMessageId.of(externalId), TrafficClass.TRANSACTIONAL),
                Recipient.ofMsisdn(Msisdn.of("998901234567")),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("Код 1234", "HAMKORBANK")),
                null,
                Timing.immediate(),
                ACCEPTED_AT);
    }
}
