package uz.hamkorbank.commhub.adapter.out.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the Hub's Kafka cluster is (§8.1).
 *
 * <p>Under {@code commhub.kafka} rather than {@code spring.kafka}: Spring Boot 4 moved its Kafka
 * auto-configuration into a module of its own, which this project does not use — the producer is built
 * explicitly in {@link KafkaProducerConfig} so the delivery guarantees of AD-03 are in code, not in a
 * yaml file someone can loosen by accident.
 *
 * @param bootstrapServers comma-separated broker list
 * @param clientId identifies the Hub in the broker's logs and quotas; the instance suffix is added by
 *     the client itself, so two instances do not collide
 */
@ConfigurationProperties("commhub.kafka")
public record KafkaConnectionProperties(String bootstrapServers, String clientId) {

    public static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";

    public static final String DEFAULT_CLIENT_ID = "notification-hub";

    public KafkaConnectionProperties {
        bootstrapServers =
                bootstrapServers == null || bootstrapServers.isBlank() ? DEFAULT_BOOTSTRAP_SERVERS : bootstrapServers;
        clientId = clientId == null || clientId.isBlank() ? DEFAULT_CLIENT_ID : clientId;
    }

    public static KafkaConnectionProperties of(String bootstrapServers) {
        return new KafkaConnectionProperties(bootstrapServers, null);
    }
}
