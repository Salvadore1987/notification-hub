package uz.hamkorbank.commhub.adapter.in.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The inbound side of the Kafka contract: which topics are consumed and with how much of the machine
 * (§8.1 IK-01, TC-01, AD-05).
 *
 * <p>Under {@code commhub.kafka.inbound} for the same reason the producer settings are under
 * {@code commhub.kafka}: Spring Boot 4 keeps its Kafka auto-configuration in a module this project
 * does not use, so the containers are built explicitly and the guarantees live in code.
 *
 * <p><strong>Concurrency is per traffic class, and that is the whole point.</strong> Each class gets
 * its own consumer group, its own containers and its own threads, so a million-item notification batch
 * cannot occupy the threads an OTP has to run on (TC-01). The defaults deliberately give the critical
 * class fewer threads than the bulk one — it needs few, but it needs them free.
 *
 * @param enabled whether this instance consumes at all; false for an instance dedicated to delivery
 * @param groupId consumer group prefix; the traffic class is appended, so the classes commit apart
 * @param criticalTopic OTP and other critical traffic, keyed by recipient (IK-01)
 * @param transactionalTopic transactional traffic, keyed by recipient
 * @param notificationTopic bulk traffic — items of batches, keyed by batchId
 * @param batchControlTopic batch headers and control commands, keyed by batchId
 * @param parseErrorTopic poison pills land here with the failure in the headers (IK-04)
 */
@ConfigurationProperties("commhub.kafka.inbound")
public record KafkaInboundProperties(
        Boolean enabled,
        String groupId,
        Topics topics,
        Concurrency concurrency,
        Integer maxPollRecords,
        Duration pollTimeout,
        Duration authExceptionRetryInterval) {

    public static final String DEFAULT_GROUP_ID = "notification-hub";

    public static final int DEFAULT_MAX_POLL_RECORDS = 100;

    public static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofSeconds(5);

    public KafkaInboundProperties {
        enabled = enabled == null || enabled;
        groupId = groupId == null || groupId.isBlank() ? DEFAULT_GROUP_ID : groupId;
        topics = topics == null ? Topics.defaults() : topics;
        concurrency = concurrency == null ? Concurrency.defaults() : concurrency;
        maxPollRecords = maxPollRecords == null ? DEFAULT_MAX_POLL_RECORDS : maxPollRecords;
        pollTimeout = pollTimeout == null || pollTimeout.isZero() ? DEFAULT_POLL_TIMEOUT : pollTimeout;
        authExceptionRetryInterval =
                authExceptionRetryInterval == null ? Duration.ofSeconds(10) : authExceptionRetryInterval;
        if (maxPollRecords < 1) {
            throw new IllegalArgumentException("commhub.kafka.inbound.max-poll-records must be positive");
        }
    }

    public static KafkaInboundProperties defaults() {
        return new KafkaInboundProperties(null, null, null, null, null, null, null);
    }

    /** Consumer group of one traffic class; separate groups keep their offsets and lag apart. */
    public String groupIdFor(String suffix) {
        return groupId + "." + suffix;
    }

    /** The five topics of §8.1 the ingress touches. */
    public record Topics(
            String critical, String transactional, String notification, String batchControl, String parseError) {

        public static final String DEFAULT_CRITICAL = "comm.inbound.critical.v1";
        public static final String DEFAULT_TRANSACTIONAL = "comm.inbound.transactional.v1";
        public static final String DEFAULT_NOTIFICATION = "comm.inbound.notification.v1";
        public static final String DEFAULT_BATCH_CONTROL = "comm.inbound.batch-control.v1";
        public static final String DEFAULT_PARSE_ERROR = "comm.inbound.parse-error.v1";

        public Topics {
            critical = blankTo(critical, DEFAULT_CRITICAL);
            transactional = blankTo(transactional, DEFAULT_TRANSACTIONAL);
            notification = blankTo(notification, DEFAULT_NOTIFICATION);
            batchControl = blankTo(batchControl, DEFAULT_BATCH_CONTROL);
            parseError = blankTo(parseError, DEFAULT_PARSE_ERROR);
        }

        public static Topics defaults() {
            return new Topics(null, null, null, null, null);
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /**
     * Threads per traffic class; each is capped by the partition count of its topic.
     *
     * <p>Critical gets the smallest share on purpose: OTP arrives in a thin stream and must never
     * wait, so what it needs is dedicated capacity rather than a lot of it. Bulk gets the largest and
     * still cannot touch the other two.
     */
    public record Concurrency(Integer critical, Integer transactional, Integer notification, Integer batchControl) {

        public Concurrency {
            critical = positiveOr(critical, 2);
            transactional = positiveOr(transactional, 4);
            notification = positiveOr(notification, 8);
            batchControl = positiveOr(batchControl, 1);
        }

        public static Concurrency defaults() {
            return new Concurrency(null, null, null, null);
        }

        private static int positiveOr(Integer value, int fallback) {
            if (value == null) {
                return fallback;
            }
            if (value < 1) {
                throw new IllegalArgumentException("commhub.kafka.inbound.concurrency values must be positive");
            }
            return value;
        }
    }
}
