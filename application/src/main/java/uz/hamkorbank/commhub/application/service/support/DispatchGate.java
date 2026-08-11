package uz.hamkorbank.commhub.application.service.support;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Whether a message may be handed to a provider right now (FR-3.2, FR-3.4, FR-5.3, FR-8.5).
 *
 * @param detail explanation kept in the status history and shown to the operator
 * @param notBefore when a deferred message is worth looking at again; {@code null} when the guard cannot
 *     tell — a paused batch or a flipped kill switch has no scheduled end. The dispatcher writes it as
 *     the next attempt time, which is what keeps a held million-item batch from being re-claimed on
 *     every single pass (ADR-0039)
 */
public record DispatchGate(Decision decision, RejectionReason reason, String detail, Instant notBefore) {

    private static final DispatchGate PROCEED = new DispatchGate(Decision.PROCEED, null, null, null);

    public DispatchGate {
        Guard.notNull(decision, "DispatchGate.decision");
        Guard.isTrue(decision != Decision.CANCEL || reason != null, "a cancelling DispatchGate requires a reason");
    }

    public static DispatchGate proceed() {
        return PROCEED;
    }

    /** Hold the message; it stays in its current status and is picked up again later. */
    public static DispatchGate defer(String detail) {
        return new DispatchGate(Decision.DEFER, null, detail, null);
    }

    /** Hold the message until a moment the guard knows — the end of a quiet-hours window (FR-5.3). */
    public static DispatchGate deferUntil(Instant notBefore, String detail) {
        return new DispatchGate(Decision.DEFER, null, detail, notBefore);
    }

    /** Cancel the message: its batch or stream was stopped (FR-3.2). */
    public static DispatchGate cancel(RejectionReason reason, String detail) {
        return new DispatchGate(Decision.CANCEL, reason, detail, null);
    }

    /** The TTL or the send window elapsed; the message must not be sent any more (FR-3.4). */
    public static DispatchGate expire(String detail) {
        return new DispatchGate(Decision.EXPIRE, RejectionReason.TTL_EXPIRED, detail, null);
    }

    /** When the deferred message becomes worth another look, if the guard knows. */
    public Optional<Instant> notBeforeOptional() {
        return Optional.ofNullable(notBefore);
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
