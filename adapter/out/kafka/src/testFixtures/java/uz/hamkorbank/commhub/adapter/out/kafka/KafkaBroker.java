package uz.hamkorbank.commhub.adapter.out.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The broker the outbound tests publish to: one container per JVM, the same image as the local stack.
 *
 * <p>Singleton for the same reason as the PostgreSQL one — starting a broker per test class would
 * dominate the run. Topics are auto-created on first publish, as they are locally; provisioning them
 * for real is an operations concern (§8.1).
 */
public final class KafkaBroker {

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/kafka:4.2.1");

    private static final KafkaContainer CONTAINER = new KafkaContainer(IMAGE);

    private KafkaBroker() {}

    public static synchronized void start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
    }

    public static String bootstrapServers() {
        start();
        return CONTAINER.getBootstrapServers();
    }

    /**
     * Reads a topic from its start and returns the records of one event id.
     *
     * <p>Filtered by event id rather than simply drained: the tests share a topic and never clean it,
     * and a topic is append-only anyway — the only records a test may assert on are the ones it caused.
     * Reading from the start is what makes the duplicate of a replayed event visible at all.
     */
    public static List<ConsumerRecord<String, String>> recordsOf(
            String topic, UUID eventId, int expected, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (matching.size() < expected && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                polled.records(topic).forEach(record -> {
                    if (eventId.toString().equals(header(record, KafkaStatusPublisherAdapter.HEADER_EVENT_ID))) {
                        matching.add(record);
                    }
                });
            }
        }
        return matching;
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
