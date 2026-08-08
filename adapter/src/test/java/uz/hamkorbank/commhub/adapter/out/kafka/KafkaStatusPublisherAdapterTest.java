package uz.hamkorbank.commhub.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Where a status event goes, keyed by what, and what happens when the broker does not answer. */
@ExtendWith(MockitoExtension.class)
class KafkaStatusPublisherAdapterTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T09:00:00Z");

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> sent;

    private final StatusEventCodec codec = new StatusEventCodec();

    @Test
    @DisplayName("a status event goes to comm.outbound.status.v1, keyed by the message (§8.1 IK-02)")
    void publishesStatusToItsTopic() {
        // Arrange
        MessageStatusEvent event = statusEvent();
        acknowledge();

        // Act
        adapter(KafkaOutboundProperties.defaults()).publishStatus(event);

        // Assert
        ProducerRecord<String, String> record = captured();
        assertThat(record.topic()).isEqualTo(KafkaOutboundProperties.DEFAULT_STATUS_TOPIC);
        assertThat(record.key()).isEqualTo(event.key().messageId().value().toString());
        assertThat(record.value()).isEqualTo(codec.write(event));
        assertThat(header(record, KafkaStatusPublisherAdapter.HEADER_EVENT_TYPE))
                .isEqualTo("MESSAGE_STATUS");
    }

    @Test
    @DisplayName("a DLQ event goes to comm.outbound.dlq.v1 (FR-3.3)")
    void publishesDlqToItsTopic() {
        // Arrange
        acknowledge();

        // Act
        adapter(KafkaOutboundProperties.defaults()).publishDlq(statusEvent());

        // Assert
        ProducerRecord<String, String> record = captured();
        assertThat(record.topic()).isEqualTo(KafkaOutboundProperties.DEFAULT_DLQ_TOPIC);
        assertThat(header(record, KafkaStatusPublisherAdapter.HEADER_EVENT_TYPE))
                .isEqualTo("MESSAGE_DLQ");
    }

    @Test
    @DisplayName("the event id travels in a header so a consumer deduplicates without parsing (FR-1.5)")
    void carriesTheEventIdInAHeader() {
        // Arrange
        MessageStatusEvent event = statusEvent();
        acknowledge();

        // Act
        adapter(KafkaOutboundProperties.defaults()).publishStatus(event);

        // Assert
        ProducerRecord<String, String> record = captured();
        assertThat(header(record, KafkaStatusPublisherAdapter.HEADER_EVENT_ID))
                .isEqualTo(event.eventId().toString());
        assertThat(header(record, KafkaStatusPublisherAdapter.HEADER_STREAM_ID)).isEqualTo("mobile-app");
        assertThat(header(record, KafkaStatusPublisherAdapter.HEADER_SCHEMA_VERSION))
                .isEqualTo(StatusEventCodec.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("a broker that refuses the record fails the publication, so the outbox keeps the event")
    void failsWhenTheBrokerRefuses() {
        // Arrange
        CompletableFuture<SendResult<String, String>> refused = new CompletableFuture<>();
        refused.completeExceptionally(new IllegalStateException("no leader for partition"));
        when(kafkaTemplate.send(anyRecord())).thenReturn(refused);
        KafkaStatusPublisherAdapter adapter = adapter(KafkaOutboundProperties.defaults());
        MessageStatusEvent event = statusEvent();

        // Act & Assert
        assertThatThrownBy(() -> adapter.publishStatus(event))
                .isInstanceOf(StatusPublicationException.class)
                .hasMessageContaining(event.eventId().toString())
                .hasRootCauseMessage("no leader for partition");
    }

    @Test
    @DisplayName("a broker that never answers fails on the configured timeout, it does not hang the relay")
    void failsWhenTheBrokerNeverAnswers() {
        // Arrange
        when(kafkaTemplate.send(anyRecord())).thenReturn(new CompletableFuture<>());
        KafkaStatusPublisherAdapter adapter =
                adapter(new KafkaOutboundProperties(null, null, null, Duration.ofMillis(50), null, null, null));
        MessageStatusEvent event = statusEvent();

        // Act & Assert
        assertThatThrownBy(() -> adapter.publishStatus(event)).isInstanceOf(StatusPublicationException.class);
    }

    private KafkaStatusPublisherAdapter adapter(KafkaOutboundProperties properties) {
        return new KafkaStatusPublisherAdapter(kafkaTemplate, codec, new PushTokenEventCodec(), properties);
    }

    @SuppressWarnings("unchecked")
    private static ProducerRecord<String, String> anyRecord() {
        return any(ProducerRecord.class);
    }

    private void acknowledge() {
        when(kafkaTemplate.send(anyRecord())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private ProducerRecord<String, String> captured() {
        verify(kafkaTemplate).send(sent.capture());
        return sent.getValue();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static MessageStatusEvent statusEvent() {
        return new MessageStatusEvent(
                UuidV7.generate(),
                OCCURRED_AT,
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
