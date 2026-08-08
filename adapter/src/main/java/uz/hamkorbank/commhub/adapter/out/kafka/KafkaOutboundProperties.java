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
 * @param pushTokenTopic device tokens the platforms have declared dead (PU-04, PU-08); its own topic
 *     because its consumer is the owner of the device registry rather than the sender of the message,
 *     and its retention has to outlive that of a status stream
 * @param analyticsTopic finished sends for the Bank's data mart (FR-6.4); its retention is measured in
 *     months rather than days, which is why it is a topic of its own and not a filter over the statuses
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
        String pushTokenTopic,
        String analyticsTopic,
        Duration sendTimeout,
        Boolean createTopics,
        Integer partitions,
        Short replicationFactor) {

    public static final String DEFAULT_STATUS_TOPIC = "comm.outbound.status.v1";

    public static final String DEFAULT_DLQ_TOPIC = "comm.outbound.dlq.v1";

    public static final String DEFAULT_PUSH_TOKEN_TOPIC = "comm.outbound.push-token.invalidated.v1";

    public static final String DEFAULT_ANALYTICS_TOPIC = "comm.outbound.events.v1";

    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);

    public KafkaOutboundProperties {
        statusTopic = statusTopic == null || statusTopic.isBlank() ? DEFAULT_STATUS_TOPIC : statusTopic;
        dlqTopic = dlqTopic == null || dlqTopic.isBlank() ? DEFAULT_DLQ_TOPIC : dlqTopic;
        pushTokenTopic = pushTokenTopic == null || pushTokenTopic.isBlank() ? DEFAULT_PUSH_TOKEN_TOPIC : pushTokenTopic;
        analyticsTopic = analyticsTopic == null || analyticsTopic.isBlank() ? DEFAULT_ANALYTICS_TOPIC : analyticsTopic;
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
        return new KafkaOutboundProperties(null, null, null, null, null, null, null, null);
    }
}
