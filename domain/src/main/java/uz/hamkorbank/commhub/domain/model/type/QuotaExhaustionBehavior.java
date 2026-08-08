package uz.hamkorbank.commhub.domain.model.type;

/** Configured behaviour when a count or cost quota is exhausted (FR-2.6). */
public enum QuotaExhaustionBehavior {
    /** Reject further messages and raise an alert. */
    BLOCK_AND_ALERT,
    /** Keep sending, only raise an alert. */
    ALERT_ONLY
}
