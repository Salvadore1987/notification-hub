package uz.hamkorbank.commhub.adapter.out.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.AnalyticsPublisherPort;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent;

/**
 * {@link AnalyticsPublisherPort} over Kafka: finished sends to {@code comm.outbound.events.v1} (FR-6.4).
 *
 * <p>Uses the same producer as the outbox relay — {@code acks=all} and idempotent — because the mart has
 * the same requirement the source systems have: an acknowledged record survives the loss of a leader.
 *
 * <p>The page is sent as a pipeline and awaited once at the end, rather than one record at a time.
 * Awaiting each record would serialise the batch into as many round trips as it has rows, and the export
 * moves pages of hundreds; awaiting them together lets the producer batch while keeping the contract the
 * exporter needs — when this returns, the whole page is on the broker.
 *
 * <p>The key is the stream. Ordering matters to the mart only within a stream, and keying by message id
 * would spread one batch job's data over every partition for no benefit.
 */
@Component
public class KafkaAnalyticsPublisherAdapter implements AnalyticsPublisherPort {

    private static final String EVENT_TYPE = "DELIVERY_EVENT";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeliveryEventCodec codec;
    private final KafkaOutboundProperties properties;

    public KafkaAnalyticsPublisherAdapter(
            KafkaTemplate<String, String> kafkaTemplate, DeliveryEventCodec codec, KafkaOutboundProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    public void publish(List<DeliveryEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<CompletableFuture<SendResult<String, String>>> sent = new ArrayList<>(events.size());
        for (DeliveryEvent event : events) {
            sent.add(kafkaTemplate.send(recordOf(event)));
        }
        awaitAll(sent, events);
    }

    private ProducerRecord<String, String> recordOf(DeliveryEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                properties.analyticsTopic(), event.streamId().value(), codec.write(event));
        record.headers()
                .add(
                        KafkaStatusPublisherAdapter.HEADER_EVENT_ID,
                        bytes(event.messageId().toString()));
        record.headers().add(KafkaStatusPublisherAdapter.HEADER_EVENT_TYPE, bytes(EVENT_TYPE));
        record.headers()
                .add(
                        KafkaStatusPublisherAdapter.HEADER_STREAM_ID,
                        bytes(event.streamId().value()));
        record.headers()
                .add(KafkaStatusPublisherAdapter.HEADER_SCHEMA_VERSION, bytes(DeliveryEventCodec.SCHEMA_VERSION));
        return record;
    }

    /**
     * Waits for the whole page and fails on the first record that did not make it.
     *
     * <p>The exporter's cursor moves only if this returns normally, so a partial page simply repeats: the
     * mart is an at-least-once feed and deduplicates by message id.
     */
    private void awaitAll(List<CompletableFuture<SendResult<String, String>>> sent, List<DeliveryEvent> events) {
        long timeoutMillis = properties.sendTimeout().toMillis();
        for (int i = 0; i < sent.size(); i++) {
            String messageId = events.get(i).messageId().toString();
            try {
                sent.get(i).get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StatusPublicationException(properties.analyticsTopic(), messageId, e);
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                throw new StatusPublicationException(properties.analyticsTopic(), messageId, e);
            }
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
