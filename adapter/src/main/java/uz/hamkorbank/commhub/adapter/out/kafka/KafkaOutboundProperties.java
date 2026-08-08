package uz.hamkorbank.commhub.adapter.out.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound Kafka contract: where statuses go and how long the relay waits for the broker (§8.1 IK-02).
 *
 * <p>Topic names are configuration rather than constants because a stream may be split into a topic of
 * its own later (§8.1) — the relay must not need a new build for that.
 *
 * @param statusTopic canonical status events for the source systems (§6.4)
 * @param dlqTopic events of a message landing in the DLQ (FR-3.3)
 * @param sendTimeout how long a publication may take before the relay treats it as failed and retries
 *     it on the next pass; it bounds how long the claimed outbox rows stay locked
 * @param createTopics whether the application creates the two topics at startup; true for the local
 *     stack, false wherever the topics are provisioned by operations with their own settings
 * @param partitions partitions of a created topic; ordering is per message key, so this is throughput
 * @param replicationFactor replicas of a created topic; 1 fits the single-broker local stack only
 */
@ConfigurationProperties("commhub.kafka.outbound")
public record KafkaOutboundProperties(
        String statusTopic,
        String dlqTopic,
        Duration sendTimeout,
        Boolean createTopics,
        Integer partitions,
        Short replicationFactor) {

    public static final String DEFAULT_STATUS_TOPIC = "comm.outbound.status.v1";

    public static final String DEFAULT_DLQ_TOPIC = "comm.outbound.dlq.v1";

    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);

    public KafkaOutboundProperties {
        statusTopic = statusTopic == null || statusTopic.isBlank() ? DEFAULT_STATUS_TOPIC : statusTopic;
        dlqTopic = dlqTopic == null || dlqTopic.isBlank() ? DEFAULT_DLQ_TOPIC : dlqTopic;
        sendTimeout = sendTimeout == null || sendTimeout.isZero() ? DEFAULT_SEND_TIMEOUT : sendTimeout;
        createTopics = createTopics != null && createTopics;
        partitions = partitions == null ? 12 : partitions;
        replicationFactor = replicationFactor == null ? (short) 1 : replicationFactor;
        if (sendTimeout.isNegative()) {
            throw new IllegalArgumentException("commhub.kafka.outbound.send-timeout must not be negative");
        }
        if (partitions < 1) {
            throw new IllegalArgumentException("commhub.kafka.outbound.partitions must be at least 1");
        }
    }

    public static KafkaOutboundProperties defaults() {
        return new KafkaOutboundProperties(null, null, null, null, null, null);
    }
}
