package uz.hamkorbank.commhub.application.policy;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Limits a push must satisfy before it is routed (PU-09, PU-11, FR-1.4).
 *
 * <p>Two ceilings, and they exist for opposite reasons.
 *
 * <p>The payload limit is the platforms' own: both APNs and FCM refuse a notification over 4 KiB
 * (PU-11). Checking it here rather than in the adapters means the source system gets a canonical reason
 * (IR-01) instead of an {@code PayloadTooLarge} discovered once per device — a recipient with four
 * devices would otherwise learn the same thing four times, at the price of four HTTP calls.
 *
 * <p>The token ceiling is the Hub's own and is a fan-out bound, not a platform rule (PU-09). One
 * logical message goes to every active device of the recipient, so the cost of a message is the number
 * of tokens the source system attached to it; a submission carrying two hundred is a source system
 * sending a broadcast through the single-message endpoint, and the honest answer is a rejection with a
 * reason rather than two hundred silent calls at the front of the OTP queue (TC-01).
 *
 * @param maxPayloadBytes size of the rendered notification; the platform limit, not a preference
 * @param maxTokensPerMessage device tokens one submission may address
 */
public record PushPolicy(int maxPayloadBytes, int maxTokensPerMessage) {

    /** Devices one customer realistically has registered; well above a phone, a tablet and a watch. */
    public static final int DEFAULT_MAX_TOKENS_PER_MESSAGE = 10;

    public PushPolicy {
        Guard.positive(maxPayloadBytes, "PushPolicy.maxPayloadBytes");
        Guard.positive(maxTokensPerMessage, "PushPolicy.maxTokensPerMessage");
    }

    public static PushPolicy defaults() {
        return new PushPolicy(PushContent.MAX_PAYLOAD_BYTES, DEFAULT_MAX_TOKENS_PER_MESSAGE);
    }

    /** Why this notification may not be sent, if it may not (PU-11). */
    public Optional<String> violation(PushContent content) {
        Guard.notNull(content, "content");
        int size = content.payloadSizeBytes();
        if (size > maxPayloadBytes) {
            return Optional.of("push payload of %d bytes exceeds the %d byte platform limit (PU-11)"
                    .formatted(size, maxPayloadBytes));
        }
        return Optional.empty();
    }

    /** Why this recipient may not be fanned out to, if it may not (PU-09). */
    public Optional<String> violation(Recipient recipient) {
        Guard.notNull(recipient, "recipient");
        int tokens = recipient.pushTokens().size();
        if (tokens > maxTokensPerMessage) {
            return Optional.of("message addresses %d device tokens, the limit is %d (PU-09)"
                    .formatted(tokens, maxTokensPerMessage));
        }
        return Optional.empty();
    }
}
