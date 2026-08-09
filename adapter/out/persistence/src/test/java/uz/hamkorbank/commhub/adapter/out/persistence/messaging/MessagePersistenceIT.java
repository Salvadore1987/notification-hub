package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

/**
 * A message read back from PostgreSQL must be the same aggregate that was written — status, history,
 * attempts and cost included (§10.1, ST-01, DB-02).
 */
class MessagePersistenceIT extends AbstractPersistenceIT {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-08T09:00:00Z");
    private static final Currency UZS = Currency.getInstance("UZS");
    private static final ProviderRef PLAYMOBILE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("PLAYMOBILE"), Channel.SMS, AdapterType.of("playmobile-http"));

    private final MessagePersistenceAdapter messages;

    MessagePersistenceIT(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, MessagePersistenceAdapter messages) {
        super(jdbcClient, transactionTemplate);
        this.messages = messages;
    }

    @BeforeEach
    void clearMessages() {
        truncate("delivery_attempt", "message_status_history", "message");
    }

    @Test
    @DisplayName("a delivered message round-trips with its full timeline and its attempts")
    void deliveredMessageRoundTrips() {
        // Arrange
        Message message = accepted("abc0000001");
        message.markValidated(Actor.system(), ACCEPTED_AT.plusSeconds(1));
        message.markRouted(Channel.SMS, PLAYMOBILE, Actor.system(), ACCEPTED_AT.plusSeconds(2));
        message.applySegments(2);
        message.applyCost(Money.of(new BigDecimal("241.0000"), UZS));
        message.markQueued(Actor.system(), ACCEPTED_AT.plusSeconds(3));
        message.markSending(Actor.system(), ACCEPTED_AT.plusSeconds(4));
        DeliveryAttempt attempt = message.startAttempt(ProviderMessageId.of("pm-0001"), ACCEPTED_AT.plusSeconds(4));
        attempt.succeed("200", ProviderMessageId.of("pm-0001"), ACCEPTED_AT.plusSeconds(5));
        message.markSentToProvider("ACCEPTD", Actor.provider("PLAYMOBILE"), ACCEPTED_AT.plusSeconds(5));
        message.markDelivered("DLVRD", Actor.provider("PLAYMOBILE"), ACCEPTED_AT.plusSeconds(30));

        // Act
        messages.save(message);
        Message restored = messages.findById(message.id()).orElseThrow();

        // Assert
        assertThat(restored.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(restored.terminalAt()).contains(ACCEPTED_AT.plusSeconds(30));
        assertThat(restored.selectedProvider()).contains(PLAYMOBILE);
        assertThat(restored.selectedChannel()).contains(Channel.SMS);
        assertThat(restored.segments()).isEqualTo(2);
        assertThat(restored.cost().orElseThrow().amount()).isEqualByComparingTo("241.0000");
        assertThat(restored.statusHistory()).hasSameSizeAs(message.statusHistory());
        assertThat(restored.statusHistory().getFirst().status()).isEqualTo(MessageStatus.ACCEPTED);
        assertThat(restored.statusHistory().getLast().status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(restored.attempts()).hasSize(1);
        assertThat(restored.attempts().getFirst().providerMessageId()).contains(ProviderMessageId.of("pm-0001"));
        assertThat(restored.attempts().getFirst().isAccepted()).isTrue();
        assertThat(restored.recipient()).isEqualTo(message.recipient());
        assertThat(restored.contents().requireForChannel(Channel.SMS))
                .isEqualTo(message.contents().requireForChannel(Channel.SMS));
        assertThat(restored.template()).contains(templateRef());
    }

    @Test
    @DisplayName("saving the same message twice appends no duplicate history (AD-03)")
    void repeatedSaveIsIdempotent() {
        // Arrange
        Message message = accepted("abc0000002");
        message.markValidated(Actor.system(), ACCEPTED_AT.plusSeconds(1));
        messages.save(message);

        // Act
        messages.save(message);

        // Assert
        Long historyRows = jdbc().sql("SELECT count(*) FROM message_status_history WHERE message_id = :id")
                .param("id", message.id().value())
                .query(Long.class)
                .single();
        assertThat(historyRows).isEqualTo(2L);
        assertThat(messages.findById(message.id()).orElseThrow().statusHistory())
                .hasSize(2);
    }

    @Test
    @DisplayName("a rejected message keeps its reason and its terminal instant (IR-01, ST-03)")
    void rejectedMessageKeepsReason() {
        // Arrange
        Message message = accepted("abc0000003");
        message.reject(
                RejectionReason.PAN_DETECTED, "PAN found in the body", Actor.system(), ACCEPTED_AT.plusSeconds(1));

        // Act
        messages.save(message);
        Message restored = messages.findById(message.id()).orElseThrow();

        // Assert
        assertThat(restored.status()).isEqualTo(MessageStatus.REJECTED);
        assertThat(restored.statusReason()).contains(RejectionReason.PAN_DETECTED);
        assertThat(restored.terminalAt()).contains(ACCEPTED_AT.plusSeconds(1));
        assertThat(restored.statusHistory().getLast().details()).isEqualTo("PAN found in the body");
    }

    @Test
    @DisplayName("a message is found by its source-system id inside its stream (FR-1.5)")
    void findsByExternalId() {
        // Arrange
        Message message = messages.save(accepted("abc0000004"));

        // Act
        Optional<Message> found =
                messages.findByExternalId(StreamId.of("mobile-app"), ExternalMessageId.of("abc0000004"));

        // Assert
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id()).isEqualTo(message.id());
        assertThat(messages.findByExternalId(StreamId.of("core-banking"), ExternalMessageId.of("abc0000004")))
                .isEmpty();
    }

    @Test
    @DisplayName("a DLR is matched to its message by the provider's own id (PM-02, SG-02)")
    void findsByProviderMessageId() {
        // Arrange
        Message message = accepted("abc0000005");
        message.markValidated(Actor.system(), ACCEPTED_AT.plusSeconds(1));
        message.markRouted(Channel.SMS, PLAYMOBILE, Actor.system(), ACCEPTED_AT.plusSeconds(2));
        message.startAttempt(ProviderMessageId.of("pm-0005"), ACCEPTED_AT.plusSeconds(3));
        messages.save(message);

        // Act
        Optional<Message> found =
                messages.findByProviderMessageId(ProviderCode.of("PLAYMOBILE"), ProviderMessageId.of("pm-0005"));

        // Assert
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id()).isEqualTo(message.id());
    }

    @Test
    @DisplayName("messages a provider accepted and never reported on are found for reconciliation (SG-03)")
    void findsMessagesAwaitingADeliveryReport() {
        // Arrange
        Message overdue = sentToProvider("abc0000010", ProviderMessageId.of("pm-0010"), ACCEPTED_AT.plusSeconds(5));
        Message justSent = sentToProvider("abc0000011", ProviderMessageId.of("pm-0011"), ACCEPTED_AT.plusSeconds(30));
        Message delivered = sentToProvider("abc0000012", ProviderMessageId.of("pm-0012"), ACCEPTED_AT.plusSeconds(5));
        delivered.markDelivered("delivered", Actor.provider("PLAYMOBILE"), ACCEPTED_AT.plusSeconds(60));
        messages.save(delivered);

        // Act: everything accepted more than a minute after acceptance is still too fresh to chase.
        List<Message> awaiting =
                messages.findAwaitingDeliveryReport(ProviderCode.of("PLAYMOBILE"), ACCEPTED_AT.plusSeconds(10), 50);

        // Assert
        assertThat(awaiting).extracting(Message::id).containsExactly(overdue.id());
        assertThat(awaiting).extracting(Message::id).doesNotContain(justSent.id(), delivered.id());
    }

    @Test
    @DisplayName("dispatchable messages are limited to the traffic class and to what may be sent now (TC-01)")
    void findsDispatchableOfTrafficClass() {
        // Arrange
        Message otp = messages.save(accepted("otp-1", TrafficClass.CRITICAL_OTP, Timing.immediate()));
        messages.save(accepted("bulk-1", TrafficClass.NOTIFICATION, Timing.immediate()));
        messages.save(accepted(
                "otp-later",
                TrafficClass.CRITICAL_OTP,
                Timing.scheduled(ACCEPTED_AT.plus(Duration.ofHours(5)), ACCEPTED_AT.plus(Duration.ofHours(6)))));

        // Act
        List<Message> dispatchable =
                messages.findDispatchable(TrafficClass.CRITICAL_OTP, ACCEPTED_AT.plusSeconds(10), 50);

        // Assert
        assertThat(dispatchable).extracting(Message::id).containsExactly(otp.id());
    }

    @Test
    @DisplayName("messages past their TTL are found for expiry, terminal ones are not (FR-3.4)")
    void findsExpiredMessages() {
        // Arrange
        Message expiring =
                messages.save(accepted("ttl-1", TrafficClass.CRITICAL_OTP, Timing.withTtl(Duration.ofMinutes(2))));
        Message alive =
                messages.save(accepted("ttl-2", TrafficClass.CRITICAL_OTP, Timing.withTtl(Duration.ofHours(4))));

        // Act
        List<Message> expired = messages.findExpired(ACCEPTED_AT.plus(Duration.ofMinutes(10)), 50);

        // Assert
        assertThat(expired).extracting(Message::id).contains(expiring.id()).doesNotContain(alive.id());
    }

    @Test
    @DisplayName("content and merge variables are stored encrypted, the routing columns are not (DB-04, DB-05)")
    void storesContentEncrypted() {
        // Arrange
        Message message = accepted("abc0000006");

        // Act
        messages.save(message);

        // Assert
        Map<String, Object> row = jdbc().sql("SELECT contents::text AS contents,"
                        + " template_variables::text AS variables,"
                        + " jsonb_typeof(contents) AS contents_type,"
                        + " recipient ->> 'msisdn' AS msisdn"
                        + " FROM message WHERE id = :id")
                .param("id", message.id().value())
                .query()
                .singleRow();
        assertThat((String) row.get("contents_type")).isEqualTo("string");
        assertThat((String) row.get("contents"))
                .startsWith("\"CH1." + TEST_KEY_ID + ".")
                .doesNotContain("1234");
        assertThat((String) row.get("variables")).startsWith("\"CH1.").doesNotContain("1234");
        assertThat((String) row.get("msisdn")).isEqualTo("998901234567");
        assertThat(messages.findById(message.id()).orElseThrow().contents().requireForChannel(Channel.SMS))
                .isEqualTo(message.contents().requireForChannel(Channel.SMS));
    }

    @Test
    @DisplayName("a row written in clear before DB-04 was switched on still reads")
    void readsRowsWrittenBeforeEncryption() {
        // Arrange
        Message message = messages.save(accepted("abc0000007"));
        jdbc().sql("UPDATE message SET contents = CAST(:contents AS jsonb),"
                        + " template_variables = CAST(:variables AS jsonb) WHERE id = :id")
                .param("contents", "{\"sms\": {\"text\": \"Код 4321\", \"originator\": \"HAMKORBANK\"}}")
                .param("variables", "{\"CODE\": \"4321\"}")
                .param("id", message.id().value())
                .update();

        // Act
        Message restored = messages.findById(message.id()).orElseThrow();

        // Assert
        assertThat(((SmsContent) restored.contents().requireForChannel(Channel.SMS)).text())
                .isEqualTo("Код 4321");
        assertThat(restored.template().orElseThrow().variables()).containsEntry("CODE", "4321");
    }

    @Test
    @DisplayName("the batch progress counts only messages that reached a terminal status (FR-3.1)")
    void countsTerminalMessagesOfBatch() {
        // Arrange
        BatchId batchId = BatchId.newId();
        Message delivered = batched("batch-1", batchId);
        delivered.markValidated(Actor.system(), ACCEPTED_AT.plusSeconds(1));
        delivered.markRouted(Channel.SMS, PLAYMOBILE, Actor.system(), ACCEPTED_AT.plusSeconds(2));
        delivered.markQueued(Actor.system(), ACCEPTED_AT.plusSeconds(3));
        delivered.markSending(Actor.system(), ACCEPTED_AT.plusSeconds(4));
        delivered.markSentToProvider("ACCEPTD", Actor.system(), ACCEPTED_AT.plusSeconds(5));
        delivered.markDelivered("DLVRD", Actor.system(), ACCEPTED_AT.plusSeconds(6));
        messages.save(delivered);
        messages.save(batched("batch-2", batchId));

        // Act
        long terminal = messages.countTerminalByBatch(batchId);

        // Assert
        assertThat(terminal).isEqualTo(1L);
    }

    private Message accepted(String externalId) {
        return accepted(externalId, TrafficClass.TRANSACTIONAL, Timing.immediate());
    }

    /** A message the provider accepted at {@code respondedAt} and has not reported on since (SG-03). */
    private Message sentToProvider(String externalId, ProviderMessageId providerMessageId, Instant respondedAt) {
        Message message = accepted(externalId);
        message.markValidated(Actor.system(), ACCEPTED_AT.plusSeconds(1));
        message.markRouted(Channel.SMS, PLAYMOBILE, Actor.system(), ACCEPTED_AT.plusSeconds(2));
        message.markQueued(Actor.system(), ACCEPTED_AT.plusSeconds(3));
        message.markSending(Actor.system(), ACCEPTED_AT.plusSeconds(4));
        message.startAttempt(providerMessageId, ACCEPTED_AT.plusSeconds(4))
                .succeed("200", providerMessageId, respondedAt);
        message.markSentToProvider("accepted", Actor.provider("PLAYMOBILE"), respondedAt);
        return messages.save(message);
    }

    private Message accepted(String externalId, TrafficClass trafficClass, Timing timing) {
        return Message.accept(
                MessageEnvelope.single(StreamId.of("mobile-app"), ExternalMessageId.of(externalId), trafficClass),
                Recipient.ofMsisdn(Msisdn.of("998901234567")),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("Код 1234", "HAMKORBANK")),
                templateRef(),
                timing,
                ACCEPTED_AT);
    }

    private Message batched(String externalId, BatchId batchId) {
        return Message.accept(
                MessageEnvelope.batched(
                        StreamId.of("mobile-app"),
                        ExternalMessageId.of(externalId),
                        TrafficClass.NOTIFICATION,
                        batchId),
                Recipient.ofMsisdn(Msisdn.of("998901234567")),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("Рассылка")),
                null,
                Timing.immediate(),
                ACCEPTED_AT);
    }

    private TemplateRef templateRef() {
        return TemplateRef.of(TemplateCode.of("OTP_LOGIN"), ContentLocale.RU, Map.of("CODE", "1234"));
    }
}
