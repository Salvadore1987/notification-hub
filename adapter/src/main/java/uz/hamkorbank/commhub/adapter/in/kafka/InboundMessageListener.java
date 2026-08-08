package uz.hamkorbank.commhub.adapter.in.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.contract.InboundMessageCodec;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Driving adapter for the three inbound message topics (§8.1 IK-01, AD-05).
 *
 * <p>Three methods and not one, because each is bound to its own container factory — that is where the
 * traffic-class isolation of TC-01 physically lives. What they do is identical and deliberately
 * trivial: parse the document into a command, hand it to the use case, log the verdict.
 *
 * <p>The traffic class comes from the topic and overrides whatever the document claims: the OTP pool is
 * built from topics, and a payload able to re-label itself would walk straight out of it.
 *
 * <p>A rejection is <strong>not</strong> a listener failure. The pipeline recorded it, persisted it and
 * published the status event the source system reads (FR-1.4); throwing here would send a
 * well-understood refusal to the parse-error topic and then redeliver it forever. Only a failure to
 * <em>process</em> — an unreadable document, a database that is down — reaches the error handler.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.kafka.inbound", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InboundMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(InboundMessageListener.class);

    private final InboundMessageCodec codec;
    private final SubmitMessage submitMessage;

    public InboundMessageListener(InboundMessageCodec codec, SubmitMessage submitMessage) {
        this.codec = Guard.notNull(codec, "codec");
        this.submitMessage = Guard.notNull(submitMessage, "submitMessage");
    }

    /** {@code comm.inbound.critical.v1} — OTP and other critical traffic, keyed by recipient (TC-01). */
    @KafkaListener(
            topics = "${commhub.kafka.inbound.topics.critical:comm.inbound.critical.v1}",
            containerFactory = KafkaConsumerConfig.CRITICAL_FACTORY)
    public void onCritical(String document) {
        accept(document, TrafficClass.CRITICAL_OTP);
    }

    /** {@code comm.inbound.transactional.v1} — transactional traffic, keyed by recipient. */
    @KafkaListener(
            topics = "${commhub.kafka.inbound.topics.transactional:comm.inbound.transactional.v1}",
            containerFactory = KafkaConsumerConfig.TRANSACTIONAL_FACTORY)
    public void onTransactional(String document) {
        accept(document, TrafficClass.TRANSACTIONAL);
    }

    /** {@code comm.inbound.notification.v1} — bulk traffic and batch items, keyed by batchId. */
    @KafkaListener(
            topics = "${commhub.kafka.inbound.topics.notification:comm.inbound.notification.v1}",
            containerFactory = KafkaConsumerConfig.NOTIFICATION_FACTORY)
    public void onNotification(String document) {
        accept(document, TrafficClass.NOTIFICATION);
    }

    private void accept(String document, TrafficClass trafficClass) {
        SubmitMessageCommand command = codec.read(document, trafficClass);
        SubmitMessageResult result = submitMessage.submit(command);
        if (result.isAccepted()) {
            LOG.debug(
                    "Accepted {} message {}/{} as {}",
                    trafficClass,
                    command.streamId(),
                    command.externalMessageId(),
                    result.status());
            return;
        }
        LOG.info(
                "Refused {} message {}/{}: {} ({})",
                trafficClass,
                command.streamId(),
                command.externalMessageId(),
                result.status(),
                result.reasonOptional().map(Enum::name).orElse("-"));
    }
}
