package uz.hamkorbank.commhub.application.service.support;

import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Whether a message may be handed to a provider right now (FR-3.2, FR-3.4, FR-5.3, FR-8.5).
 *
 * @param detail explanation kept in the status history and shown to the operator
 */
public record DispatchGate(Decision decision, RejectionReason reason, String detail) {

    private static final DispatchGate PROCEED = new DispatchGate(Decision.PROCEED, null, null);

    public DispatchGate {
        Guard.notNull(decision, "DispatchGate.decision");
        Guard.isTrue(decision != Decision.CANCEL || reason != null, "a cancelling DispatchGate requires a reason");
    }

    public static DispatchGate proceed() {
        return PROCEED;
    }

    /** Hold the message; it stays in its current status and is picked up again later. */
    public static DispatchGate defer(String detail) {
        return new DispatchGate(Decision.DEFER, null, detail);
    }

    /** Cancel the message: its batch or stream was stopped (FR-3.2). */
    public static DispatchGate cancel(RejectionReason reason, String detail) {
        return new DispatchGate(Decision.CANCEL, reason, detail);
    }

    /** The TTL or the send window elapsed; the message must not be sent any more (FR-3.4). */
    public static DispatchGate expire(String detail) {
        return new DispatchGate(Decision.EXPIRE, RejectionReason.TTL_EXPIRED, detail);
    }

    public boolean isProceed() {
        return decision == Decision.PROCEED;
    }

    /** What the guards decided. */
    public enum Decision {
        PROCEED,
        DEFER,
        CANCEL,
        EXPIRE
    }
}
