package uz.hamkorbank.commhub.domain.model.type;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Lifecycle of a batch send (SRS §6.1, FR-3.1, FR-3.2). */
public enum BatchStatus {

    /** Header accepted, items may still be uploaded in chunks (FR-1.6). */
    ACCEPTED,
    /** Items are being processed by the pipeline. */
    PROCESSING,
    /** Paused by an operator; processing can resume (FR-3.2). */
    PAUSED,
    /** Stopped by an operator; remaining messages are cancelled (FR-3.2). */
    STOPPED,
    /** Every item reached a terminal message status. */
    COMPLETED;

    private static final Map<BatchStatus, Set<BatchStatus>> TRANSITIONS;

    static {
        Map<BatchStatus, Set<BatchStatus>> transitions = new EnumMap<>(BatchStatus.class);
        transitions.put(ACCEPTED, EnumSet.of(PROCESSING, PAUSED, STOPPED, COMPLETED));
        transitions.put(PROCESSING, EnumSet.of(PAUSED, STOPPED, COMPLETED));
        transitions.put(PAUSED, EnumSet.of(PROCESSING, STOPPED));
        transitions.put(STOPPED, EnumSet.noneOf(BatchStatus.class));
        transitions.put(COMPLETED, EnumSet.noneOf(BatchStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    public boolean isTerminal() {
        return this == STOPPED || this == COMPLETED;
    }

    public boolean canTransitionTo(BatchStatus next) {
        return next != null && TRANSITIONS.get(this).contains(next);
    }

    public Set<BatchStatus> allowedTransitions() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }
}
