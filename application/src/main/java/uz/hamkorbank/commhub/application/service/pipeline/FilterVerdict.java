package uz.hamkorbank.commhub.application.service.pipeline;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of the delivery filters (FR-5.1…FR-5.4).
 *
 * <p>Unlike other stages the filters know a third answer: quiet hours configured to defer do not
 * reject the message, they hold it until the window closes (FR-5.3).
 *
 * @param deferUntil instant sending may resume; set only for {@link Decision#DEFER}
 */
public record FilterVerdict(Decision decision, RejectionReason reason, String detail, Instant deferUntil) {

    private static final FilterVerdict PASSED = new FilterVerdict(Decision.PASS, null, null, null);

    public FilterVerdict {
        Guard.notNull(decision, "FilterVerdict.decision");
        Guard.isTrue(decision != Decision.REJECT || reason != null, "a rejecting FilterVerdict requires a reason");
        Guard.isTrue(decision != Decision.DEFER || deferUntil != null, "a deferring FilterVerdict requires deferUntil");
    }

    public static FilterVerdict passed() {
        return PASSED;
    }

    public static FilterVerdict rejected(RejectionReason reason, String detail) {
        return new FilterVerdict(Decision.REJECT, reason, detail, null);
    }

    /** Hold the message until the quiet-hours window closes (FR-5.3). */
    public static FilterVerdict deferred(Instant until, String detail) {
        return new FilterVerdict(Decision.DEFER, null, detail, until);
    }

    public boolean isPassed() {
        return decision == Decision.PASS;
    }

    public boolean isRejected() {
        return decision == Decision.REJECT;
    }

    public boolean isDeferred() {
        return decision == Decision.DEFER;
    }

    public Optional<Instant> deferUntilOptional() {
        return Optional.ofNullable(deferUntil);
    }

    /** What the filters decided (FR-5.1…FR-5.4). */
    public enum Decision {
        PASS,
        REJECT,
        DEFER
    }
}
