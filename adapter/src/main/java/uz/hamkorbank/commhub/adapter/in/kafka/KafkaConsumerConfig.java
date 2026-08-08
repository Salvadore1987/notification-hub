package uz.hamkorbank.commhub.adapter.in.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import uz.hamkorbank.commhub.adapter.out.kafka.KafkaConnectionProperties;

/**
 * The consumer side of the Kafka ingress: one container factory per traffic class (§8.1, TC-01, AD-05).
 *
 * <p>Four factories rather than one with four listeners on it, because the isolation of TC-01 is only
 * real if it reaches the threads. Each class gets its own consumer group, its own client id and its own
 * thread pool, so a bulk notification topic that is hours behind cannot delay an OTP by a millisecond,
 * and the lag of each class is separately visible in the broker (OBS-01).
 *
 * <p>Records are consumed as strings and parsed by the listener rather than by a deserializer. A
 * deserializer that fails takes the whole poll with it and gives the error handler nothing but bytes;
 * parsing inside the listener means a malformed record is one record, with its own offset, that goes to
 * {@code comm.inbound.parse-error.v1} while the rest of the batch is processed (IK-04).
 *
 * <p>Offsets are committed per record after the listener returns. Processing is idempotent by dedup key
 * (FR-1.5), so a redelivery after a crash costs a lookup and not a second message.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "commhub.kafka.inbound", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConsumerConfig {

    /** Suffixes of the consumer groups; part of the operational contract, so they are constants. */
    static final String GROUP_CRITICAL = "critical";

    static final String GROUP_TRANSACTIONAL = "transactional";

    static final String GROUP_NOTIFICATION = "notification";

    static final String GROUP_BATCH_CONTROL = "batch-control";

    /** Bean names the listeners reference; a typo here is a listener that silently never starts. */
    public static final String CRITICAL_FACTORY = "criticalListenerContainerFactory";

    public static final String TRANSACTIONAL_FACTORY = "transactionalListenerContainerFactory";

    public static final String NOTIFICATION_FACTORY = "notificationListenerContainerFactory";

    public static final String BATCH_CONTROL_FACTORY = "batchControlListenerContainerFactory";

    @Bean(CRITICAL_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> criticalListenerContainerFactory(
            KafkaConnectionProperties connection, KafkaInboundProperties inbound, CommonErrorHandler errorHandler) {
        return factory(
                connection,
                inbound,
                errorHandler,
                GROUP_CRITICAL,
                inbound.concurrency().critical());
    }

    @Bean(TRANSACTIONAL_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> transactionalListenerContainerFactory(
            KafkaConnectionProperties connection, KafkaInboundProperties inbound, CommonErrorHandler errorHandler) {
        return factory(
                connection,
                inbound,
                errorHandler,
                GROUP_TRANSACTIONAL,
                inbound.concurrency().transactional());
    }

    @Bean(NOTIFICATION_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> notificationListenerContainerFactory(
            KafkaConnectionProperties connection, KafkaInboundProperties inbound, CommonErrorHandler errorHandler) {
        return factory(
                connection,
                inbound,
                errorHandler,
                GROUP_NOTIFICATION,
                inbound.concurrency().notification());
    }

    @Bean(BATCH_CONTROL_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> batchControlListenerContainerFactory(
            KafkaConnectionProperties connection, KafkaInboundProperties inbound, CommonErrorHandler errorHandler) {
        return factory(
                connection,
                inbound,
                errorHandler,
                GROUP_BATCH_CONTROL,
                inbound.concurrency().batchControl());
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> factory(
            KafkaConnectionProperties connection,
            KafkaInboundProperties inbound,
            CommonErrorHandler errorHandler,
            String group,
            int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory(connection, inbound, group));
        factory.setConcurrency(concurrency);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        factory.getContainerProperties().setPollTimeout(inbound.pollTimeout().toMillis());
        factory.getContainerProperties().setAuthExceptionRetryInterval(inbound.authExceptionRetryInterval());
        return factory;
    }

    private static ConsumerFactory<String, String> consumerFactory(
            KafkaConnectionProperties connection, KafkaInboundProperties inbound, String group) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.bootstrapServers());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, connection.clientId() + "." + group);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, inbound.groupIdFor(group));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Ничего не теряем на первом запуске: неизвестной группе отдаём топик с начала.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, inbound.maxPollRecords());
        return new DefaultKafkaConsumerFactory<>(config);
    }
}
