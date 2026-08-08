package uz.hamkorbank.commhub.adapter.out.kafka;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.port.out.StatusPublisherPort;

/**
 * {@link StatusPublisherPort} over Kafka: statuses to {@code comm.outbound.status.v1}, DLQ events to
 * {@code comm.outbound.dlq.v1} (§8.1 IK-02).
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

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StatusEventCodec codec;
    private final KafkaOutboundProperties properties;

    public KafkaStatusPublisherAdapter(
            KafkaTemplate<String, String> kafkaTemplate, StatusEventCodec codec, KafkaOutboundProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
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

    private void send(String topic, String eventType, MessageStatusEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, codec.keyOf(event), codec.write(event));
        header(record, HEADER_EVENT_ID, event.eventId().toString());
        header(record, HEADER_EVENT_TYPE, eventType);
        header(record, HEADER_STREAM_ID, event.key().streamId().value());
        header(record, HEADER_SCHEMA_VERSION, StatusEventCodec.SCHEMA_VERSION);
        try {
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StatusPublicationException(topic, event, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new StatusPublicationException(topic, event, e);
        }
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
