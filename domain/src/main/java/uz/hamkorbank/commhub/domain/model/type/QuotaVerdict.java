package uz.hamkorbank.commhub.domain.model.type;

/** Result of a quota check for a candidate send (FR-2.6). */
public enum QuotaVerdict {
    ALLOWED,
    /** Over the limit, but the configuration only asks for an alert. */
    ALERT,
    /** Over the limit and configured to block: the message is rejected. */
    BLOCKED;

    public boolean permitsSending() {
        return this != BLOCKED;
    }

    public boolean requiresAlert() {
        return this != ALLOWED;
    }
}
