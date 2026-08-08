package uz.hamkorbank.commhub.adapter.out.kafka;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.application.port.out.StatusPublisherPort;

/**
 * {@link StatusPublisherPort} over Kafka: statuses to {@code comm.outbound.status.v1}, DLQ events to
 * {@code comm.outbound.dlq.v1} (§8.1 IK-02) and invalidated device tokens to
 * {@code comm.outbound.push-token.invalidated.v1} (PU-04).
 *
 * <p><strong>Synchronous by design.</strong> The relay may only mark an outbox row published once the
 * broker has the record, so this waits for the acknowledgement instead of handing back a future. A
 * timeout is a failure like any other: the row stays unpublished and goes out again, which is the
 * at-least-once side of AD-03.
 *
 * <p>Every record carries the event id, its type and the stream in headers as well as in the body, so a
 * consumer can deduplicate and route without parsing the payload (FR-1.5).
 */
@Component
public class KafkaStatusPublisherAdapter implements StatusPublisherPort {

    static final String HEADER_EVENT_ID = "commhub-event-id";
    static final String HEADER_EVENT_TYPE = "commhub-event-type";
    static final String HEADER_STREAM_ID = "commhub-stream-id";
    static final String HEADER_SCHEMA_VERSION = "commhub-schema-version";

    private static final String STATUS_EVENT = "MESSAGE_STATUS";
    private static final String DLQ_EVENT = "MESSAGE_DLQ";
    private static final String PUSH_TOKEN_EVENT = "PUSH_TOKEN_INVALIDATED";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StatusEventCodec codec;
    private final PushTokenEventCodec pushTokenCodec;
    private final KafkaOutboundProperties properties;

    public KafkaStatusPublisherAdapter(
            KafkaTemplate<String, String> kafkaTemplate,
            StatusEventCodec codec,
            PushTokenEventCodec pushTokenCodec,
            KafkaOutboundProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.pushTokenCodec = pushTokenCodec;
        this.properties = properties;
    }

    @Override
    public void publishStatus(MessageStatusEvent event) {
        send(properties.statusTopic(), STATUS_EVENT, event);
    }

    @Override
    public void publishDlq(MessageStatusEvent event) {
        send(properties.dlqTopic(), DLQ_EVENT, event);
    }

    @Override
    public void publishPushTokenInvalidated(PushTokenInvalidatedEvent event) {
        send(
                properties.pushTokenTopic(),
                PUSH_TOKEN_EVENT,
                PushTokenEventCodec.SCHEMA_VERSION,
                pushTokenCodec.keyOf(event),
                pushTokenCodec.write(event),
                event.eventId().toString(),
                event.streamId().value());
    }

    private void send(String topic, String eventType, MessageStatusEvent event) {
        send(
                topic,
                eventType,
                StatusEventCodec.SCHEMA_VERSION,
                codec.keyOf(event),
                codec.write(event),
                event.eventId().toString(),
                event.key().streamId().value());
    }

    private void send(
            String topic,
            String eventType,
            String schemaVersion,
            String key,
            String body,
            String eventId,
            String streamId) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, body);
        header(record, HEADER_EVENT_ID, eventId);
        header(record, HEADER_EVENT_TYPE, eventType);
        header(record, HEADER_STREAM_ID, streamId);
        header(record, HEADER_SCHEMA_VERSION, schemaVersion);
        try {
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StatusPublicationException(topic, eventId, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new StatusPublicationException(topic, eventId, e);
        }
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
