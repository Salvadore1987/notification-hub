package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxEventType;
import uz.hamkorbank.commhub.application.port.out.PendingOutboxEvent;
import uz.hamkorbank.commhub.application.port.out.PushDelivery;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Per-device rows of a push fan-out and the outbound token event (PU-04, PU-09). */
class PushDeliveryPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");

    private static final ProviderRef FCM = new ProviderRef(
            ProviderId.of(UUID.fromString("019158a0-0000-7000-8000-0000000000fc")),
            ProviderCode.of("FCM"),
            Channel.PUSH,
            AdapterType.of("fcm-http"));

    private final PushDeliveryPersistenceAdapter deliveries;
    private final OutboxPersistenceAdapter outbox;

    PushDeliveryPersistenceIT(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            PushDeliveryPersistenceAdapter deliveries,
            OutboxPersistenceAdapter outbox) {
        super(jdbcClient, transactionTemplate);
        this.deliveries = deliveries;
        this.outbox = outbox;
    }

    @BeforeEach
    void clean() {
        truncate("push_delivery", "outbox_event");
    }

    @Test
    @DisplayName("PU-09: a fan-out over two devices is two rows under one attempt")
    void recordsOneRowPerDevice() {
        // Arrange
        MessageId messageId = MessageId.of(UuidV7.generate());
        AttemptId attemptId = AttemptId.newId();

        // Act
        transactions()
                .executeWithoutResult(status -> deliveries.record(List.of(
                        delivery(messageId, attemptId, "device-a", PushPlatform.ANDROID, accepted()),
                        delivery(messageId, attemptId, "device-b", PushPlatform.IOS, retired()))));

        // Assert
        List<PushDelivery> rows = deliveries.findByMessage(messageId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.attemptId()).isEqualTo(attemptId);
            assertThat(row.provider().code()).isEqualTo(ProviderCode.of("FCM"));
        });
        assertThat(rows.stream().map(PushDelivery::platform))
                .containsExactlyInAnyOrder(PushPlatform.ANDROID, PushPlatform.IOS);
        assertThat(rows.stream().filter(row -> row.outcome().tokenInvalidated()).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("DB-04: only the hash of a token is stored, never the token itself")
    void storesOnlyTheTokenHash() {
        // Arrange
        MessageId messageId = MessageId.of(UuidV7.generate());
        AddressHash hash = AddressHash.ofPushToken(PushToken.of("device-a", PushPlatform.ANDROID));

        // Act
        transactions()
                .executeWithoutResult(status -> deliveries.record(
                        List.of(delivery(messageId, AttemptId.newId(), "device-a", PushPlatform.ANDROID, accepted()))));

        // Assert
        assertThat(deliveries.findByMessage(messageId).getFirst().tokenHash()).isEqualTo(hash);
        assertThat(jdbc().sql("SELECT count(*) FROM push_delivery WHERE token_hash = :hash")
                        .param("hash", hash.value())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("PU-09: device rows are written inside the attempt's transaction or not at all")
    void refusesToWriteOutsideATransaction() {
        // Arrange
        MessageId messageId = MessageId.of(UuidV7.generate());
        List<PushDelivery> rows =
                List.of(delivery(messageId, AttemptId.newId(), "device-a", PushPlatform.ANDROID, accepted()));

        // Act & Assert
        assertThatThrownBy(() -> deliveries.record(rows)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("PU-04: a token event survives the outbox round trip with the token it names")
    void storesAndReadsBackATokenEvent() {
        // Arrange
        PushTokenInvalidatedEvent payload = new PushTokenInvalidatedEvent(
                UuidV7.generate(),
                NOW,
                StreamId.of("mobile-app"),
                ClientId.of("C123"),
                PushToken.of("device-a", PushPlatform.ANDROID),
                ProviderCode.of("FCM"),
                "UNREGISTERED");

        // Act
        transactions().executeWithoutResult(status -> outbox.append(OutboxEvent.pushTokenInvalidated(payload)));
        List<PendingOutboxEvent> pending = transactions().execute(status -> outbox.pollUnpublished(10));

        // Assert
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().type()).isEqualTo(OutboxEventType.PUSH_TOKEN_INVALIDATED);
        assertThat(pending.getFirst().aggregateId()).isEqualTo("C123");
        assertThat(pending.getFirst().payload()).isEqualTo(payload);
    }

    private static PushDelivery delivery(
            MessageId messageId,
            AttemptId attemptId,
            String token,
            PushPlatform platform,
            PushDelivery.Outcome outcome) {
        return new PushDelivery(
                messageId,
                attemptId,
                FCM,
                AddressHash.ofPushToken(PushToken.of(token, platform)),
                platform,
                ProviderMessageId.of("projects/x/messages/1"),
                outcome);
    }

    private static PushDelivery.Outcome accepted() {
        return new PushDelivery.Outcome(AttemptResult.ACCEPTED, "200", null, false, NOW);
    }

    private static PushDelivery.Outcome retired() {
        return new PushDelivery.Outcome(AttemptResult.REJECTED, "UNREGISTERED", "app uninstalled", true, NOW);
    }
}
