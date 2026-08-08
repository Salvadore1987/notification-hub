package uz.hamkorbank.commhub.domain.model.type;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Canonical message status model shared by every channel (SRS §6.3, MP-04).
 *
 * <p>Provider-specific statuses are mapped onto these values by the provider adapters (ST-03,
 * §18.1–18.2), so the pipeline and the source systems only ever see canonical statuses.
 *
 * <p>The enum also owns the transition table of the message state machine (ST-01, ST-02):
 *
 * <pre>
 * ACCEPTED → VALIDATED → ROUTED → QUEUED → SENDING → SENT_TO_PROVIDER → DELIVERED
 *                                                                     → UNDELIVERED / EXPIRED
 * </pre>
 */
public enum MessageStatus {

    /** Accepted from a source system, nothing validated yet. */
    ACCEPTED,
    /** Payload, addresses and references passed validation (FR-1.4). */
    VALIDATED,
    /** Channel and provider are chosen (FR-2.3, MP-05). */
    ROUTED,
    /** Waiting for a sender thread of its traffic class (TC-01). */
    QUEUED,
    /** A provider call is in flight. */
    SENDING,
    /** The provider accepted the message; terminal status for push (PU-12). */
    SENT_TO_PROVIDER,
    /** A retry or a provider failover is scheduled (PR-01, FR-6.3). */
    RETRYING,
    /** Delivery confirmed by the provider DLR. */
    DELIVERED,
    /** The provider reported a non-delivery. */
    UNDELIVERED,
    /** TTL elapsed before delivery (FR-3.4). */
    EXPIRED,
    /** Rejected by validation, suppression, quiet hours or quotas (FR-5.1…FR-5.4). */
    REJECTED,
    /** Suppressed by the idempotency check (FR-1.5). */
    DUPLICATE,
    /** Cancelled by a batch/stream stop or a kill switch (FR-3.2). */
    CANCELLED,
    /** All attempts exhausted; the message is moved to the DLQ (FR-3.3). */
    FAILED;

    private static final Set<MessageStatus> TERMINAL_STATUSES = Collections.unmodifiableSet(
            EnumSet.of(DELIVERED, UNDELIVERED, EXPIRED, REJECTED, DUPLICATE, CANCELLED, FAILED));

    private static final Map<MessageStatus, Set<MessageStatus>> TRANSITIONS;

    static {
        Map<MessageStatus, Set<MessageStatus>> transitions = new EnumMap<>(MessageStatus.class);
        transitions.put(ACCEPTED, EnumSet.of(VALIDATED, REJECTED, DUPLICATE, CANCELLED, EXPIRED));
        transitions.put(VALIDATED, EnumSet.of(ROUTED, REJECTED, CANCELLED, EXPIRED));
        transitions.put(ROUTED, EnumSet.of(QUEUED, REJECTED, CANCELLED, EXPIRED));
        transitions.put(QUEUED, EnumSet.of(SENDING, REJECTED, CANCELLED, EXPIRED));
        transitions.put(SENDING, EnumSet.of(SENT_TO_PROVIDER, RETRYING, FAILED, UNDELIVERED, CANCELLED, EXPIRED));
        transitions.put(SENT_TO_PROVIDER, EnumSet.of(DELIVERED, UNDELIVERED, RETRYING, EXPIRED));
        transitions.put(RETRYING, EnumSet.of(SENDING, FAILED, CANCELLED, EXPIRED));
        // ST-02: after a manual DLQ retry the lifecycle resumes with a new delivery attempt.
        transitions.put(FAILED, EnumSet.of(QUEUED));
        transitions.put(DELIVERED, EnumSet.noneOf(MessageStatus.class));
        transitions.put(UNDELIVERED, EnumSet.noneOf(MessageStatus.class));
        transitions.put(EXPIRED, EnumSet.noneOf(MessageStatus.class));
        transitions.put(REJECTED, EnumSet.noneOf(MessageStatus.class));
        transitions.put(DUPLICATE, EnumSet.noneOf(MessageStatus.class));
        transitions.put(CANCELLED, EnumSet.noneOf(MessageStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /** Terminal statuses of ST-02; {@link #FAILED} may still be reopened by a DLQ retry. */
    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }

    /** Whether the message is still on its way through the pipeline (used by partial indexes, DB-05). */
    public boolean isInFlight() {
        return !isTerminal();
    }

    public boolean canTransitionTo(MessageStatus next) {
        return next != null && TRANSITIONS.get(this).contains(next);
    }

    public Set<MessageStatus> allowedTransitions() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }

    public static Set<MessageStatus> terminalStatuses() {
        return TERMINAL_STATUSES;
    }
}
