package uz.hamkorbank.commhub.application.mapper;

import java.util.LinkedHashMap;
import java.util.Map;
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
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
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

    /**
     * Reserved key of the data payload that asks for collapsing (PU-03, §9.4).
     *
     * <p>A transport instruction rather than business data, so it is read out of the payload and not
     * forwarded to the device: the application would receive a field it never sent.
     */
    String COLLAPSE_KEY_FIELD = "collapseKey";

    /** Push submission for one device token; a multi-device recipient yields several (PU-09). */
    default PushSubmission toPushSubmission(Message message, ProviderRef provider, PushToken token) {
        PushContent content = contentOf(message, Channel.PUSH, PushContent.class);
        return new PushSubmission(
                provider,
                message.id(),
                token,
                withoutTransportFields(content),
                message.timing(),
                collapseKeyOf(message, content),
                toContext(message));
    }

    /**
     * Notification group this message supersedes, when the source system asked for one (PU-03).
     *
     * <p>Never for {@code CRITICAL_OTP}, whatever the payload says: collapsing means an undelivered
     * notification is replaced by the next one of the same group, and a one-time password the customer
     * never sees is exactly the failure the OTP path exists to prevent (TC-01). A password is also not
     * "the same notification, only fresher" — the previous one is still valid until it expires.
     */
    private static String collapseKeyOf(Message message, PushContent content) {
        if (message.envelope().trafficClass() == TrafficClass.CRITICAL_OTP) {
            return null;
        }
        String requested = content.data().get(COLLAPSE_KEY_FIELD);
        return requested == null || requested.isBlank() ? null : requested.trim();
    }

    /** The payload the device receives: business data only. */
    private static PushContent withoutTransportFields(PushContent content) {
        if (!content.data().containsKey(COLLAPSE_KEY_FIELD)) {
            return content;
        }
        Map<String, String> data = new LinkedHashMap<>(content.data());
        data.remove(COLLAPSE_KEY_FIELD);
        return new PushContent(content.title(), content.body(), data, content.deepLink(), content.image());
    }

    private static <T> T contentOf(Message message, Channel channel, Class<T> type) {
        Guard.notNull(message, "message");
        return type.cast(message.contents().requireForChannel(channel));
    }
}
