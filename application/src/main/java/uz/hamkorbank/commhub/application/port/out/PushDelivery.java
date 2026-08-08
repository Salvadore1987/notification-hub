package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What one device made of one push (PU-09).
 *
 * <p>The delivery attempt of §6.1 stays what it has always been — one turn of the sending saga, whose
 * outcome is the message's — and this is the row underneath it, one per token. PU-09 asks for exactly
 * that pair: an aggregated status per recipient, which is the message's own, and a detailed one per
 * token, which is here. Collapsing them would lose the answer to the question operations actually asks
 * about push, "which of the customer's devices got it".
 *
 * <p>The token appears only as its hash, like everywhere else outside the fan-out (DB-04): the platform
 * is kept alongside because "all iOS devices are failing" is a diagnosis and the hash alone cannot give
 * it.
 *
 * @param attemptId attempt of the saga this fan-out belonged to; several rows share it
 * @param providerMessageId id the platform assigned to this device's copy, when it assigns one
 */
public record PushDelivery(
        MessageId messageId,
        AttemptId attemptId,
        ProviderRef provider,
        AddressHash tokenHash,
        PushPlatform platform,
        ProviderMessageId providerMessageId,
        Outcome outcome) {

    public PushDelivery {
        Guard.notNull(messageId, "PushDelivery.messageId");
        Guard.notNull(attemptId, "PushDelivery.attemptId");
        Guard.notNull(provider, "PushDelivery.provider");
        Guard.notNull(tokenHash, "PushDelivery.tokenHash");
        Guard.notNull(platform, "PushDelivery.platform");
        Guard.notNull(outcome, "PushDelivery.outcome");
    }

    public Optional<ProviderMessageId> providerMessageIdOptional() {
        return Optional.ofNullable(providerMessageId);
    }

    /**
     * The platform's verdict for this device.
     *
     * @param responseCode HTTP status or the platform's own code, kept verbatim (PR-03, PU-08)
     * @param errorDescription the {@code reason} of an APNs answer or the {@code status} of an FCM one
     * @param tokenInvalidated whether this answer is what retired the token (PU-04, PU-08)
     */
    public record Outcome(
            AttemptResult result,
            String responseCode,
            String errorDescription,
            boolean tokenInvalidated,
            Instant respondedAt) {

        public Outcome {
            Guard.notNull(result, "PushDelivery.Outcome.result");
            Guard.notNull(respondedAt, "PushDelivery.Outcome.respondedAt");
        }
    }
}
