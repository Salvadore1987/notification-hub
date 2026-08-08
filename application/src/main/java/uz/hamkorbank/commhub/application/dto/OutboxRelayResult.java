package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of one pass of the outbox relay (AD-03).
 *
 * @param published events the broker acknowledged in this pass
 * @param failed events whose publication failed; they stay in the outbox and are retried
 * @param more whether the pass filled its batch and should be repeated at once instead of waiting
 *     for the next tick — that is what lets a backlog drain faster than the poll interval
 */
public record OutboxRelayResult(int published, int failed, boolean more) {

    public OutboxRelayResult {
        Guard.notNegative(published, "OutboxRelayResult.published");
        Guard.notNegative(failed, "OutboxRelayResult.failed");
    }

    public static OutboxRelayResult none() {
        return new OutboxRelayResult(0, 0, false);
    }

    public boolean isIdle() {
        return published == 0 && failed == 0;
    }
}
