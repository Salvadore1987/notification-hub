package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of one TTL sweep (FR-3.4).
 *
 * @param expired messages moved to {@code EXPIRED} in this run
 * @param more whether the sweep hit its limit and should run again immediately
 */
public record ExpireMessagesResult(int expired, boolean more) {

    public ExpireMessagesResult {
        Guard.notNegative(expired, "ExpireMessagesResult.expired");
    }

    public static ExpireMessagesResult none() {
        return new ExpireMessagesResult(0, false);
    }
}
