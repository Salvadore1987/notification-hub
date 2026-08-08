package uz.hamkorbank.commhub.adapter.out.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The producer the outbox relay publishes through (AD-03, §8.1).
 *
 * <p>The guarantees are set here and not left to configuration, because the outbox is only as good as
 * they are: {@code acks=all} so an acknowledged event survives the loss of the leader, and
 * {@code enable.idempotence=true} so the producer's own retries cannot turn one event into several
 * records. Together with the relay marking a row published only after the acknowledgement, that is
 * at-least-once with duplicates only across a crash — which consumers absorb by {@code eventId}.
 *
 * <p>Values are JSON strings produced by {@link StatusEventCodec}; the schema of the topic lives in
 * {@code resources/schema} and is registered in the Schema Registry by operations (NF-08). A serializer
 * that talks to the registry itself would put the registry on the sending path of every status event —
 * a second thing that can be down while a message is being delivered.
 */
@Configuration
public class KafkaProducerConfig {

    /** Bounds a single send; the relay's own timeout is the one that decides when a row is retried. */
    private static final int REQUEST_TIMEOUT_MS = 5_000;

    private static final int LINGER_MS = 5;

    /**
     * Producer metrics — batch size, record send rate, retries — come from the client (OBS-01).
     *
     * <p>Optional for the same reason the consumer's are: whether this deployment has a registry at all
     * is decided in {@code bootstrap}, and the relay must publish with or without one.
     */
    private final ObjectProvider<MeterRegistry> meters;

    private final KafkaSecurityConfigurer security;

    public KafkaProducerConfig(ObjectProvider<MeterRegistry> meters, KafkaSecurityConfigurer security) {
        this.meters = meters;
        this.security = security;
    }

    @Bean
    public ProducerFactory<String, String> statusProducerFactory(
            KafkaConnectionProperties connection, KafkaOutboundProperties outbound) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.bootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, connection.clientId());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS);
        config.put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS);
        config.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                Math.max((int) outbound.sendTimeout().toMillis(), REQUEST_TIMEOUT_MS + LINGER_MS));
        security.apply(config);
        DefaultKafkaProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(config);
        meters.ifAvailable(registry -> producerFactory.addListener(new MicrometerProducerListener<>(registry)));
        return producerFactory;
    }

    @Bean
    public KafkaTemplate<String, String> statusKafkaTemplate(ProducerFactory<String, String> statusProducerFactory) {
        return new KafkaTemplate<>(statusProducerFactory);
    }

    /**
     * Creates the outbound topics where nobody else does — the local stack and the test environments.
     *
     * <p>Off by default: in the Bank's contour topics come with partition counts, retention and ACLs
     * that operations owns, and an application that creates them on startup would quietly override that.
     */
    @Bean
    @ConditionalOnProperty(prefix = "commhub.kafka.outbound", name = "create-topics", havingValue = "true")
    public KafkaAdmin outboundKafkaAdmin(KafkaConnectionProperties connection) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, connection.bootstrapServers()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "commhub.kafka.outbound", name = "create-topics", havingValue = "true")
    public NewTopic statusTopic(KafkaOutboundProperties outbound) {
        return TopicBuilder.name(outbound.statusTopic())
                .partitions(outbound.partitions())
                .replicas(outbound.replicationFactor())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "commhub.kafka.outbound", name = "create-topics", havingValue = "true")
    public NewTopic dlqTopic(KafkaOutboundProperties outbound) {
        return TopicBuilder.name(outbound.dlqTopic())
                .partitions(outbound.partitions())
                .replicas(outbound.replicationFactor())
                .build();
    }

    /** Invalidated device tokens (PU-04); same partitioning, its own retention in the Bank's contour. */
    @Bean
    @ConditionalOnProperty(prefix = "commhub.kafka.outbound", name = "create-topics", havingValue = "true")
    public NewTopic pushTokenTopic(KafkaOutboundProperties outbound) {
        return TopicBuilder.name(outbound.pushTokenTopic())
                .partitions(outbound.partitions())
                .replicas(outbound.replicationFactor())
                .build();
    }

    /** Finished sends for the data mart (FR-6.4); keyed by stream, retained far longer than a status. */
    @Bean
    @ConditionalOnProperty(prefix = "commhub.kafka.outbound", name = "create-topics", havingValue = "true")
    public NewTopic analyticsTopic(KafkaOutboundProperties outbound) {
        return TopicBuilder.name(outbound.analyticsTopic())
                .partitions(outbound.partitions())
                .replicas(outbound.replicationFactor())
                .build();
    }
}
