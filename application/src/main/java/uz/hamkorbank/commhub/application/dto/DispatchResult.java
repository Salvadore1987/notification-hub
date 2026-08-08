package uz.hamkorbank.commhub.application.dto;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of one turn of the sending saga for one message (AD-04, PR-01).
 *
 * @param outcome what the saga decided; drives whether the caller re-enqueues the message
 * @param provider provider the attempt went to; {@code null} when nothing was submitted
 * @param attemptNumber number of the attempt that was made; 0 when none was
 */
public record DispatchResult(
        MessageId messageId, MessageStatus status, DispatchOutcome outcome, ProviderRef provider, int attemptNumber) {

    public DispatchResult {
        Guard.notNull(messageId, "DispatchResult.messageId");
        Guard.notNull(status, "DispatchResult.status");
        Guard.notNull(outcome, "DispatchResult.outcome");
        Guard.notNegative(attemptNumber, "DispatchResult.attemptNumber");
    }

    public static DispatchResult of(
            MessageId messageId, MessageStatus status, DispatchOutcome outcome, ProviderRef provider, int attempt) {
        return new DispatchResult(messageId, status, outcome, provider, attempt);
    }

    /** The saga did nothing: the message was not dispatchable at this moment. */
    public static DispatchResult skipped(MessageId messageId, MessageStatus status, DispatchOutcome outcome) {
        return new DispatchResult(messageId, status, outcome, null, 0);
    }

    public Optional<ProviderRef> providerOptional() {
        return Optional.ofNullable(provider);
    }

    /** Whether the message must be picked up again by the dispatcher (PR-01, FR-6.3). */
    public boolean needsAnotherTurn() {
        return outcome == DispatchOutcome.RETRY_SCHEDULED || outcome == DispatchOutcome.DEFERRED;
    }

    /** What the saga did with the message (AD-04). */
    public enum DispatchOutcome {
        /** Handed over to the provider, which acknowledged it (§6.3 {@code SENT_TO_PROVIDER}). */
        SENT,
        /** The attempt failed and another one is scheduled, possibly on the next provider (PR-01). */
        RETRY_SCHEDULED,
        /** Every attempt and fallback is exhausted; the message went to the DLQ (FR-3.3). */
        FAILED,
        /** The provider reported a permanent non-delivery for this message (§18.1, §18.2). */
        UNDELIVERED,
        /** Held back: send window not open, quiet hours or a paused batch (FR-5.3, FR-8.5, FR-3.2). */
        DEFERRED,
        /** Cancelled by a batch/stream stop or the kill switch (FR-3.2). */
        CANCELLED,
        /** TTL elapsed before the message reached a provider (FR-3.4). */
        EXPIRED,
        /** No route is available any more, e.g. every provider of the channel is down (FR-2.2). */
        NO_ROUTE,
        /** Nothing to do: the message already reached a terminal status (ST-02). */
        SKIPPED
    }
}
