package uz.hamkorbank.commhub.domain.model.type;

/**
 * Traffic class driving priority isolation (SRS §6.2, TC-01, TC-02).
 *
 * <p>Each class gets its own Kafka topics, thread pools and provider rate-limit budgets, so that
 * bulk {@link #NOTIFICATION} load can never breach the {@link #CRITICAL_OTP} SLA (TC-01).
 */
public enum TrafficClass {

    /** OTP and other critical codes: p99 ≤ 5 s from accept to provider hand-off. */
    CRITICAL_OTP(Priority.REALTIME),
    /** Event-driven notifications (payments, application statuses): p99 ≤ 30 s. */
    TRANSACTIONAL(Priority.NORMAL),
    /** Bulk campaigns: throttled, quiet hours and frequency capping apply. */
    NOTIFICATION(Priority.LOW);

    private final Priority defaultPriority;

    TrafficClass(Priority defaultPriority) {
        this.defaultPriority = defaultPriority;
    }

    /** Priority used when the source system does not request one explicitly (PM-03). */
    public Priority defaultPriority() {
        return defaultPriority;
    }

    /** Quiet hours only apply to classes other than OTP and transactional (FR-5.3). */
    public boolean respectsQuietHours() {
        return this == NOTIFICATION;
    }

    /** Frequency capping is limited to bulk traffic (FR-5.4). */
    public boolean respectsFrequencyCapping() {
        return this == NOTIFICATION;
    }

    /** A stop/kill switch must not affect OTP unless it is requested explicitly (FR-3.2). */
    public boolean stoppableByDefault() {
        return this != CRITICAL_OTP;
    }
}
