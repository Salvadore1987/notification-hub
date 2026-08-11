package uz.hamkorbank.commhub.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.dto.OutboxRelayResult;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.application.port.in.PublishOutboxEvents;
import uz.hamkorbank.commhub.application.port.in.command.PublishOutboxEventsCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.PendingOutboxEvent;
import uz.hamkorbank.commhub.application.port.out.StatusPublisherPort;
import uz.hamkorbank.commhub.application.service.PublishOutboxEventsService;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * The transactional outbox end to end: PostgreSQL, the relay and a real broker (AD-03, QA-06, §8.1).
 *
 * <p>What this is here to prove is not that the happy path publishes — it is what happens when it does
 * not. An instance dying between the broker's acknowledgement and the commit must lose nothing, a
 * broker that is down must leave the event where it is, and two relays running at once must not each
 * publish the same row.
 */
@Tag("integration")
@SpringJUnitConfig(OutboxRelayIT.RelayTestConfig.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(properties = "commhub.kafka.outbound.create-topics=true")
class OutboxRelayIT {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T09:00:00Z");
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final String STATUS_TOPIC = "it.outbound.status.v1";
    private static final String DLQ_TOPIC = "it.outbound.dlq.v1";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final OutboxPort outbox;
    private final PublishOutboxEvents relay;
    private final PublishOutboxEvents crashingRelay;
    private final PublishOutboxEvents brokenRelay;
    private final JsonMapper mapper = JsonMapper.builder().build();

    OutboxRelayIT(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            OutboxPort outbox,
            @Qualifier("relay") PublishOutboxEvents relay,
            @Qualifier("crashingRelay") PublishOutboxEvents crashingRelay,
            @Qualifier("brokenRelay") PublishOutboxEvents brokenRelay) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.outbox = outbox;
        this.relay = relay;
        this.crashingRelay = crashingRelay;
        this.brokenRelay = brokenRelay;
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.sql("TRUNCATE TABLE outbox_event CASCADE").update();
    }

    @Test
    @DisplayName("an event appended in a business transaction reaches the status topic in the §6.4 format")
    void publishesAnAppendedEvent() {
        // Arrange
        MessageStatusEvent event = statusEvent(MessageStatus.DELIVERED);
        append(OutboxEvent.messageStatus(event));

        // Act
        OutboxRelayResult result = relay.publish(PublishOutboxEventsCommand.defaults());

        // Assert
        assertThat(result.published()).isEqualTo(1);
        List<ConsumerRecord<String, String>> records =
                KafkaBroker.recordsOf(STATUS_TOPIC, event.eventId(), 1, POLL_TIMEOUT);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key())
                .isEqualTo(event.key().messageId().value().toString());
        JsonNode json = mapper.readTree(records.getFirst().value());
        assertThat(json.get("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(json.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(json.get("segments").asInt()).isEqualTo(2);
        assertThat(publishedAt(event)).isNotNull();
    }

    @Test
    @DisplayName("a DLQ event goes to its own topic, the status event to the status topic (§8.1 IK-02)")
    void routesEventsByType() {
        // Arrange
        MessageStatusEvent failed = statusEvent(MessageStatus.FAILED);
        transactions.executeWithoutResult(status -> {
            outbox.append(OutboxEvent.messageStatus(failed));
            outbox.append(OutboxEvent.messageDlq(failed));
        });

        // Act
        relay.publish(PublishOutboxEventsCommand.defaults());

        // Assert
        assertThat(KafkaBroker.recordsOf(STATUS_TOPIC, failed.eventId(), 1, POLL_TIMEOUT))
                .hasSize(1);
        List<ConsumerRecord<String, String>> dlq = KafkaBroker.recordsOf(DLQ_TOPIC, failed.eventId(), 1, POLL_TIMEOUT);
        assertThat(dlq).hasSize(1);
        assertThat(mapper.readTree(dlq.getFirst().value()).get("eventId").asString())
                .isEqualTo(failed.eventId().toString());
    }

    @Test
    @DisplayName("an instance dying after the broker acknowledged loses nothing and republishes (QA-06, AD-03)")
    void republishesAfterACrashBetweenSendAndCommit() {
        // Arrange
        MessageStatusEvent event = statusEvent(MessageStatus.SENT_TO_PROVIDER);
        append(OutboxEvent.messageStatus(event));

        // Act — the broker gets the record, then the instance dies before the transaction commits
        assertThatThrownBy(() -> crashingRelay.publish(PublishOutboxEventsCommand.defaults()))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(publishedAt(event)).as("the crashed transaction rolled back").isNull();
        OutboxRelayResult afterRestart = relay.publish(PublishOutboxEventsCommand.defaults());

        // Assert — at-least-once: on the topic twice, under one event id, and nothing lost
        assertThat(afterRestart.published()).isEqualTo(1);
        List<ConsumerRecord<String, String>> records =
                KafkaBroker.recordsOf(STATUS_TOPIC, event.eventId(), 2, POLL_TIMEOUT);
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> assertThat(
                        mapper.readTree(record.value()).get("eventId").asString())
                .isEqualTo(event.eventId().toString()));
        assertThat(publishedAt(event)).isNotNull();
    }

    @Test
    @DisplayName("a broker that is down leaves the event for the next pass, with the reason on the row")
    void keepsAnUnpublishableEvent() {
        // Arrange
        MessageStatusEvent event = statusEvent(MessageStatus.QUEUED);
        append(OutboxEvent.messageStatus(event));

        // Act
        OutboxRelayResult result = brokenRelay.publish(PublishOutboxEventsCommand.defaults());

        // Assert
        assertThat(result.published()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(publishedAt(event)).isNull();
        assertThat(column(event, "attempts", Integer.class)).isEqualTo(1);
        assertThat(column(event, "last_error", String.class)).contains("broker is down");
    }

    @Test
    @DisplayName("a claimed batch is invisible to a second relay, so no event is published twice (AD-03)")
    void claimedRowsAreSkippedByOtherInstances() {
        // Arrange
        append(OutboxEvent.messageStatus(statusEvent(MessageStatus.QUEUED)));

        // Act — a second transaction polls while the first still holds its claim
        List<PendingOutboxEvent> seenByTheSecond = transactions.execute(status -> {
            assertThat(outbox.pollUnpublished(10)).hasSize(1);
            return pollOnAnotherConnection();
        });

        // Assert
        assertThat(seenByTheSecond).isEmpty();
    }

    private List<PendingOutboxEvent> pollOnAnotherConnection() {
        CompletableFuture<List<PendingOutboxEvent>> other =
                CompletableFuture.supplyAsync(() -> transactions.execute(status -> outbox.pollUnpublished(10)));
        try {
            return other.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void append(OutboxEvent event) {
        transactions.executeWithoutResult(status -> outbox.append(event));
    }

    private Instant publishedAt(MessageStatusEvent event) {
        OffsetDateTime published = column(event, "published_at", OffsetDateTime.class);
        return published == null ? null : published.toInstant();
    }

    private <T> T column(MessageStatusEvent event, String column, Class<T> type) {
        return jdbc.sql("SELECT " + column + " FROM outbox_event WHERE payload ->> 'eventId' = :id")
                .param("id", event.eventId().toString())
                .query(type)
                .optional()
                .orElse(null);
    }

    private static MessageStatusEvent statusEvent(MessageStatus status) {
        return new MessageStatusEvent(
                UuidV7.generate(),
                OCCURRED_AT,
                new MessageKey(
                        StreamId.of("mobile-app"),
                        null,
                        MessageId.of(UUID.randomUUID()),
                        ExternalMessageId.of("abc-" + UUID.randomUUID()),
                        CorrelationId.of("corr-1")),
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                status,
                "ACCEPTD",
                status == MessageStatus.FAILED
                        ? MessageStatusEvent.StatusReason.of(RejectionReason.PROVIDER_REJECTED, "code 411")
                        : null,
                2);
    }

    /** Models the instance dying: an {@link Error} is not what the relay catches, so the transaction aborts. */
    static final class SimulatedCrash extends Error {

        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("the instance died after the broker acknowledged");
        }
    }

    @Configuration
    @Import({
        AbstractPersistenceIT.PersistenceTestConfig.class,
        KafkaProducerConfig.class,
        StatusEventCodec.class,
        PushTokenEventCodec.class,
        KafkaStatusPublisherAdapter.class
    })
    static class RelayTestConfig {

        @Bean
        KafkaConnectionProperties kafkaConnectionProperties() {
            return KafkaConnectionProperties.of(KafkaBroker.bootstrapServers());
        }

        /**
         * The client-security configurer of SEC-01, configured with nothing.
         *
         * <p>The local broker takes no credentials, and a test that authenticated would test the broker's
         * configuration instead of the Hub's. The configurer still has to exist: production wires it into
         * both clients, and a test context without it would prove those clients can be built without it.
         */
        @Bean
        KafkaSecurityConfigurer kafkaSecurityConfigurer() {
            return new KafkaSecurityConfigurer(KafkaSecurityProperties.none());
        }

        @Bean
        KafkaOutboundProperties kafkaOutboundProperties() {
            return new KafkaOutboundProperties(
                    STATUS_TOPIC, DLQ_TOPIC, null, null, Duration.ofSeconds(20), true, 1, (short) 1);
        }

        @Bean
        PublishOutboxEvents relay(OutboxPort outbox, StatusPublisherPort publisher, ClockPort clock) {
            return new PublishOutboxEventsService(outbox, publisher, clock);
        }

        @Bean
        PublishOutboxEvents crashingRelay(OutboxPort outbox, StatusPublisherPort publisher, ClockPort clock) {
            return new PublishOutboxEventsService(outbox, crashAfterSending(publisher), clock);
        }

        @Bean
        PublishOutboxEvents brokenRelay(OutboxPort outbox, ClockPort clock) {
            return new PublishOutboxEventsService(outbox, unreachableBroker(), clock);
        }

        /** Publishes for real, then kills the instance — the record is out, the transaction is not. */
        private static StatusPublisherPort crashAfterSending(StatusPublisherPort delegate) {
            return new StatusPublisherPort() {

                @Override
                public void publishStatus(MessageStatusEvent event) {
                    delegate.publishStatus(event);
                    throw new SimulatedCrash();
                }

                @Override
                public void publishDlq(MessageStatusEvent event) {
                    delegate.publishDlq(event);
                    throw new SimulatedCrash();
                }

                @Override
                public void publishPushTokenInvalidated(PushTokenInvalidatedEvent event) {
                    delegate.publishPushTokenInvalidated(event);
                    throw new SimulatedCrash();
                }
            };
        }

        private static StatusPublisherPort unreachableBroker() {
            return new StatusPublisherPort() {

                @Override
                public void publishStatus(MessageStatusEvent event) {
                    throw new IllegalStateException("broker is down");
                }

                @Override
                public void publishDlq(MessageStatusEvent event) {
                    throw new IllegalStateException("broker is down");
                }

                @Override
                public void publishPushTokenInvalidated(PushTokenInvalidatedEvent event) {
                    throw new IllegalStateException("broker is down");
                }
            };
        }
    }
}
