package uz.hamkorbank.commhub.application.service.pipeline;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.policy.PanPolicy;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContent;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Validation of a message that is about to enter routing (FR-1.4, PU-11, SEC-05).
 *
 * <p>Address formats are already guaranteed by the value objects — {@code Msisdn} enforces
 * {@code 9989xxxxxxxx}, {@code EmailAddress} RFC 5322 — so what is left here are the cross-field
 * rules: the recipient must be reachable on a planned channel, the payload must fit the channel
 * limits, and no content may carry a full card number.
 *
 * <p>Runs after templating, so the checks see the text that will actually be sent (FR-4.3).
 *
 * <p>A card number is always counted; whether it also rejects the message is {@link PanPolicy}'s decision, and
 * for SMS the answer is always yes (SEC-05).
 */
@Component
public class MessageValidator {

    private final PanDetector panDetector;
    private final PanPolicy panPolicy;
    private final MetricsPort metrics;

    public MessageValidator(PanDetector panDetector, PanPolicy panPolicy, MetricsPort metrics) {
        this.panDetector = Guard.notNull(panDetector, "panDetector");
        this.panPolicy = Guard.notNull(panPolicy, "panPolicy");
        this.metrics = Guard.notNull(metrics, "metrics");
    }

    /** Validates the final content and addressing of a message (FR-1.4). */
    public PipelineVerdict validate(Message message) {
        Guard.notNull(message, "message");
        if (message.deliverableChannels().isEmpty()) {
            return PipelineVerdict.rejected(
                    RejectionReason.VALIDATION_FAILED,
                    "recipient has no address for the planned channels: "
                            + message.channelPlan().channels());
        }
        for (Channel channel : message.deliverableChannels()) {
            PipelineVerdict verdict = validateContent(message.contents().requireForChannel(channel), channel);
            if (verdict.isRejected()) {
                return verdict;
            }
        }
        return PipelineVerdict.passed();
    }

    private PipelineVerdict validateContent(MessageContent content, Channel channel) {
        return switch (content) {
            case SmsContent sms -> validateText(sms.text(), channel);
            case PushContent push -> validatePush(push, channel);
            case EmailContent email -> validateEmail(email, channel);
        };
    }

    private PipelineVerdict validatePush(PushContent push, Channel channel) {
        if (push.exceedsPayloadLimit()) {
            return PipelineVerdict.rejected(
                    RejectionReason.VALIDATION_FAILED,
                    "push payload of %d bytes exceeds the %d byte platform limit (PU-11)"
                            .formatted(push.payloadSizeBytes(), PushContent.MAX_PAYLOAD_BYTES));
        }
        return validateText(push.title() + " " + push.body(), channel);
    }

    private PipelineVerdict validateEmail(EmailContent email, Channel channel) {
        PipelineVerdict subject = validateText(email.subject(), channel);
        if (subject.isRejected()) {
            return subject;
        }
        PipelineVerdict text = validateText(email.textBody(), channel);
        return text.isRejected() ? text : validateText(email.htmlBody(), channel);
    }

    /** PCI DSS: a card number must never leave the Hub in message content (SEC-05). */
    private PipelineVerdict validateText(String text, Channel channel) {
        if (!panDetector.containsPan(text)) {
            return PipelineVerdict.passed();
        }
        boolean blocked = panPolicy.blocksOn(channel);
        // Всегда метрика, никогда лог: строка с номером карты в логе — это тот же PAN, только в другом
        // хранилище (SEC-05, OBS-03). Алерт по OBS-04 строится на этой метрике.
        metrics.panDetected(channel, blocked);
        if (!blocked) {
            return PipelineVerdict.passed();
        }
        return PipelineVerdict.rejected(
                RejectionReason.PAN_DETECTED, "content for channel %s contains a card number".formatted(channel));
    }
}
