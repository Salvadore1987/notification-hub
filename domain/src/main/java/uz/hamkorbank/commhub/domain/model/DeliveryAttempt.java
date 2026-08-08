package uz.hamkorbank.commhub.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One call to a provider for one message (§6.1, §10.1 {@code delivery_attempt}, PR-03).
 *
 * <p>Records the provider, the provider-side message id, the response code, the latency and the error
 * classification that drives retry, failover and circuit-breaker decisions (PM-01, PR-01).
 */
public final class DeliveryAttempt extends AggregateRoot<AttemptId> {

    public static final int MAX_RESPONSE_CODE_LENGTH = 64;
    public static final int MAX_ERROR_DESCRIPTION_LENGTH = 1024;

    private final MessageId messageId;
    private final ProviderRef provider;
    private final int attemptNumber;
    private final Instant requestAt;

    private ProviderMessageId providerMessageId;
    private AttemptResult result;
    private String responseCode;
    private ErrorClass errorClass;
    private String errorDescription;
    private Instant responseAt;

    private DeliveryAttempt(
            AttemptId id,
            MessageId messageId,
            ProviderRef provider,
            int attemptNumber,
            ProviderMessageId providerMessageId,
            Instant requestAt) {
        super(id);
        this.messageId = Guard.notNull(messageId, "DeliveryAttempt.messageId");
        this.provider = Guard.notNull(provider, "DeliveryAttempt.provider");
        this.attemptNumber = Guard.positive(attemptNumber, "DeliveryAttempt.attemptNumber");
        this.requestAt = Guard.notNull(requestAt, "DeliveryAttempt.requestAt");
        this.providerMessageId = providerMessageId;
        this.result = AttemptResult.PENDING;
    }

    /**
     * Opens an attempt right before the provider call.
     *
     * @param providerMessageId provider-side id when the Hub generates it (Playmobile, §9.1);
     *     {@code null} when the provider assigns it in its response (SMS Gate, §9.2)
     */
    public static DeliveryAttempt start(
            AttemptId id,
            MessageId messageId,
            ProviderRef provider,
            int attemptNumber,
            ProviderMessageId providerMessageId,
            Instant requestAt) {
        return new DeliveryAttempt(id, messageId, provider, attemptNumber, providerMessageId, requestAt);
    }

    /** The provider acknowledged the submission. */
    public void succeed(String responseCode, ProviderMessageId assignedProviderMessageId, Instant responseAt) {
        complete(AttemptResult.ACCEPTED, responseCode, null, null, responseAt);
        if (assignedProviderMessageId != null) {
            this.providerMessageId = assignedProviderMessageId;
        }
    }

    /** The provider rejected the submission permanently, e.g. Playmobile 202/404 (§18.1). */
    public void reject(String responseCode, String errorDescription, Instant responseAt) {
        complete(AttemptResult.REJECTED, responseCode, ErrorClass.NON_RETRYABLE, errorDescription, responseAt);
    }

    /** The call failed; {@code errorClass} decides retry, failover or circuit breaker (PM-01, PR-01). */
    public void fail(String responseCode, ErrorClass errorClass, String errorDescription, Instant responseAt) {
        Guard.notNull(errorClass, "errorClass");
        complete(AttemptResult.ERROR, responseCode, errorClass, errorDescription, responseAt);
    }

    /** Connect or read timeout: always retryable (PR-01). */
    public void timeout(Instant responseAt) {
        complete(AttemptResult.TIMEOUT, null, ErrorClass.RETRYABLE, "provider call timed out", responseAt);
    }

    public boolean isComplete() {
        return result != AttemptResult.PENDING;
    }

    public boolean isAccepted() {
        return result == AttemptResult.ACCEPTED;
    }

    /** Whether the sending saga may try again, possibly on another provider (PR-01, FR-6.3). */
    public boolean isRetryable() {
        return errorClass == ErrorClass.RETRYABLE;
    }

    /** Whether the failure must open the provider circuit breaker, e.g. Playmobile 102 (§18.1). */
    public boolean isBlockingForProvider() {
        return errorClass == ErrorClass.BLOCKING;
    }

    public Optional<Duration> latency() {
        return responseAt == null ? Optional.empty() : Optional.of(Duration.between(requestAt, responseAt));
    }

    public MessageId messageId() {
        return messageId;
    }

    public ProviderRef provider() {
        return provider;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public Instant requestAt() {
        return requestAt;
    }

    public Optional<ProviderMessageId> providerMessageId() {
        return Optional.ofNullable(providerMessageId);
    }

    public AttemptResult result() {
        return result;
    }

    public Optional<String> responseCode() {
        return Optional.ofNullable(responseCode);
    }

    public Optional<ErrorClass> errorClass() {
        return Optional.ofNullable(errorClass);
    }

    public Optional<String> errorDescription() {
        return Optional.ofNullable(errorDescription);
    }

    public Optional<Instant> responseAt() {
        return Optional.ofNullable(responseAt);
    }

    private void complete(
            AttemptResult outcome, String responseCode, ErrorClass errorClass, String description, Instant responseAt) {
        Guard.isTrue(
                !isComplete(),
                "delivery attempt %d is already complete with result %s".formatted(attemptNumber, result));
        Guard.notNull(responseAt, "responseAt");
        Guard.isTrue(!responseAt.isBefore(requestAt), "responseAt must not be before requestAt");
        Guard.maxLength(responseCode, MAX_RESPONSE_CODE_LENGTH, "DeliveryAttempt.responseCode");
        Guard.maxLength(description, MAX_ERROR_DESCRIPTION_LENGTH, "DeliveryAttempt.errorDescription");
        this.result = outcome;
        this.responseCode = responseCode;
        this.errorClass = errorClass;
        this.errorDescription = description;
        this.responseAt = responseAt;
    }
}
