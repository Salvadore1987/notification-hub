package uz.hamkorbank.commhub.domain.model.type;

/** What to do with a message that falls inside quiet hours (FR-5.3). */
public enum QuietHoursBehavior {
    /** Hold the message until the window closes. */
    DEFER,
    /** Reject with {@code QUIET_HOURS}. */
    REJECT
}
