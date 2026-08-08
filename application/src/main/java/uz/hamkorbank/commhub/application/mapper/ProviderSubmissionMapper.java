package uz.hamkorbank.commhub.application.mapper;

import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderTemplateBinding;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Builds the channel submissions handed to the provider adapters (MP-05, PM-01, EM-01, PU-01).
 *
 * <p>The canonical {@code Message} is translated once, here; the adapters then only speak their own
 * protocol, which is what keeps a new provider an adapter-only change (AR-04).
 */
@Mapper(componentModel = "spring")
public interface ProviderSubmissionMapper {

    /** Envelope attributes every adapter needs (MP-01, PM-03). */
    default SubmissionContext toContext(Message message) {
        MessageEnvelope envelope = message.envelope();
        return new SubmissionContext(
                envelope.trafficClass(), envelope.priority(), envelope.correlationId(), message.isTest());
    }

    /** SMS submission; {@code providerMessageId} is set for providers that require one (§9.1). */
    default SmsSubmission toSmsSubmission(
            Message message,
            ProviderRef provider,
            ProviderMessageId providerMessageId,
            ProviderTemplateBinding binding) {
        return new SmsSubmission(
                provider,
                message.id(),
                providerMessageId,
                message.recipient().msisdn(),
                contentOf(message, Channel.SMS, SmsContent.class),
                message.timing(),
                binding,
                toContext(message));
    }

    /** Email submission for the SMTP adapter (EM-01). */
    default EmailSubmission toEmailSubmission(Message message, ProviderRef provider) {
        return new EmailSubmission(
                provider,
                message.id(),
                message.recipient().email(),
                contentOf(message, Channel.EMAIL, EmailContent.class),
                toContext(message));
    }

    /** Push submission for one device token; a multi-device recipient yields several (PU-09). */
    default PushSubmission toPushSubmission(Message message, ProviderRef provider, PushToken token) {
        return new PushSubmission(
                provider,
                message.id(),
                token,
                contentOf(message, Channel.PUSH, PushContent.class),
                message.timing(),
                null,
                toContext(message));
    }

    private static <T> T contentOf(Message message, Channel channel, Class<T> type) {
        Guard.notNull(message, "message");
        return type.cast(message.contents().requireForChannel(channel));
    }
}
