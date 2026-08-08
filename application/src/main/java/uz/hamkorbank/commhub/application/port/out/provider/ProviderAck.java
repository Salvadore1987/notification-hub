package uz.hamkorbank.commhub.application.port.out.provider;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Answer of a provider to one submission (PM-01, SG-01, PU-04, PU-08).
 *
 * <p>The adapter has already translated the provider's vocabulary: it classifies the outcome
 * ({@link AttemptResult}) and, for failures, the error class that drives retry, failover and circuit
 * breaker (§18.1, §18.2, PR-01). The saga never parses provider codes itself.
 *
 * @param providerMessageId id assigned by the provider, when it assigns one (SMS Gate {@code id})
 * @param providerStatus raw provider status kept for the audit trail and the outbound event (§6.4)
 * @param invalidRecipient the address is no longer valid: push token unregistered (PU-04, PU-08) or
 *     the number is blacklisted (§18.2 code 7) — the caller invalidates it
 */
public record ProviderAck(
        AttemptResult result,
        ProviderMessageId providerMessageId,
        String providerStatus,
        String responseCode,
        ErrorClass errorClass,
        String errorDescription,
        boolean invalidRecipient,
        Instant respondedAt) {

    public ProviderAck {
        Guard.notNull(result, "ProviderAck.result");
        Guard.notNull(respondedAt, "ProviderAck.respondedAt");
        Guard.isTrue(result != AttemptResult.PENDING, "a ProviderAck must describe a completed call");
        Guard.isTrue(
                result == AttemptResult.ACCEPTED || errorClass != null,
                "a failed ProviderAck must carry an ErrorClass (PR-01)");
    }

    /** The provider accepted the submission. */
    public static ProviderAck accepted(ProviderMessageId providerMessageId, String responseCode, Instant respondedAt) {
        return new ProviderAck(
                AttemptResult.ACCEPTED, providerMessageId, null, responseCode, null, null, false, respondedAt);
    }

    /** The provider rejected the submission permanently, e.g. Playmobile 401–406 (§18.1). */
    public static ProviderAck rejected(String responseCode, String description, Instant respondedAt) {
        return new ProviderAck(
                AttemptResult.REJECTED,
                null,
                null,
                responseCode,
                ErrorClass.NON_RETRYABLE,
                description,
                false,
                respondedAt);
    }

    /** The call failed; the class decides retry, failover or circuit breaker (PR-01). */
    public static ProviderAck failed(
            String responseCode, ErrorClass errorClass, String description, Instant respondedAt) {
        return new ProviderAck(
                AttemptResult.ERROR, null, null, responseCode, errorClass, description, false, respondedAt);
    }

    /** Connect or read timeout: always retryable (PR-01). */
    public static ProviderAck timedOut(Instant respondedAt) {
        return new ProviderAck(
                AttemptResult.TIMEOUT,
                null,
                null,
                null,
                ErrorClass.RETRYABLE,
                "provider call timed out",
                false,
                respondedAt);
    }

    public boolean isAccepted() {
        return result == AttemptResult.ACCEPTED;
    }

    /** Whether the saga may try again, on this or on the next provider (PR-01, FR-6.3). */
    public boolean isRetryable() {
        return errorClass == ErrorClass.RETRYABLE;
    }

    /** Whether the failure must open the circuit breaker of the provider, e.g. Playmobile 102 (§18.1). */
    public boolean isBlocking() {
        return errorClass == ErrorClass.BLOCKING;
    }

    public Optional<ProviderMessageId> providerMessageIdOptional() {
        return Optional.ofNullable(providerMessageId);
    }

    /** Returns a copy that also invalidates the recipient address (PU-04, PU-08, §18.2). */
    public ProviderAck withInvalidRecipient() {
        return new ProviderAck(
                result,
                providerMessageId,
                providerStatus,
                responseCode,
                errorClass,
                errorDescription,
                true,
                respondedAt);
    }
}
