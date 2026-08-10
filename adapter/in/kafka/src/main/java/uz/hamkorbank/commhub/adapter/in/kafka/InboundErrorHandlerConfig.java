package uz.hamkorbank.commhub.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

/**
 * What happens to a record the ingress cannot process (§8.1 IK-04).
 *
 * <p>Two different failures need two different answers, and conflating them is how a queue stops
 * moving. A record that is <em>wrong</em> — malformed JSON, an unknown enum, an MSISDN that is not
 * {@code 9989xxxxxxxx} — will be just as wrong on the hundredth redelivery, so it goes straight to
 * {@code comm.inbound.parse-error.v1} and the partition moves on. A record that hit something
 * <em>transient</em> — the database, a lock, the broker — is retried with a growing backoff, because
 * dropping a valid OTP because the database blinked is not an option.
 *
 * <p>Every poison pill is logged at ERROR with its coordinates; that log line is the alert of IK-04,
 * and a non-empty parse-error topic means a source system is emitting documents nobody can read.
 */
@Configuration
public class InboundErrorHandlerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(InboundErrorHandlerConfig.class);

    /** Header carrying the field pointer of a contract violation, so triage does not need the stack. */
    public static final String HEADER_FAILED_FIELD = "commhub-failed-field";

    /** Header naming the topic the record came from; the parse-error topic mixes all four. */
    public static final String HEADER_ORIGIN_TOPIC = "commhub-origin-topic";

    private static final long INITIAL_BACKOFF_MS = 500L;

    private static final double BACKOFF_MULTIPLIER = 2.0;

    private static final long MAX_BACKOFF_MS = 10_000L;

    private static final long MAX_ELAPSED_MS = 60_000L;

    @Bean
    public CommonErrorHandler inboundErrorHandler(
            KafkaOperations<String, String> statusKafkaTemplate, KafkaInboundProperties inbound) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer(statusKafkaTemplate, inbound), backOff());
        // Contract violations are not retried: the document is wrong, not the moment.
        handler.addNotRetryableExceptions(InboundContractException.class, DomainValidationException.class);
        handler.setAckAfterHandle(true);
        return handler;
    }

    private static DeadLetterPublishingRecoverer recoverer(
            KafkaOperations<String, String> template, KafkaInboundProperties inbound) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(inbound.topics().parseError(), -1));
        recoverer.setHeadersFunction((record, exception) -> {
            Headers headers = new RecordHeaders();
            headers.add(HEADER_ORIGIN_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8));
            String field = fieldOf(exception);
            if (field != null) {
                headers.add(HEADER_FAILED_FIELD, field.getBytes(StandardCharsets.UTF_8));
            }
            LOG.error(
                    "Poison pill on {}-{} offset {} routed to {} ({}): {}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    inbound.topics().parseError(),
                    field == null ? "no field" : field,
                    exception.getMessage());
            return headers;
        });
        return recoverer;
    }

    private static ExponentialBackOff backOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MS);
        // После минуты попыток запись уезжает в parse-error: партиция важнее одной записи.
        backOff.setMaxElapsedTime(MAX_ELAPSED_MS);
        return backOff;
    }

    private static String fieldOf(Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof InboundContractException contract) {
                return contract.field();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
