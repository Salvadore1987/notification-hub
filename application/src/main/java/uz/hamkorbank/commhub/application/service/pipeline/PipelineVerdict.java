package uz.hamkorbank.commhub.application.service.pipeline;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Outcome of one pipeline stage: pass, or reject with a canonical reason (IR-01).
 *
 * <p>A rejection is a normal result, not an exception: the message still gets a status, a history
 * entry and an outbound event, and the REST adapter turns the reason into a {@code problem+json} code
 * (FR-1.4, ST-01).
 *
 * @param detail human-readable explanation for the operator and the source system
 */
public record PipelineVerdict(RejectionReason reason, String detail) {

    private static final PipelineVerdict PASSED = new PipelineVerdict(null, null);

    /** The stage found nothing to complain about. */
    public static PipelineVerdict passed() {
        return PASSED;
    }

    public static PipelineVerdict rejected(RejectionReason reason, String detail) {
        Guard.notNull(reason, "reason");
        return new PipelineVerdict(reason, detail);
    }

    public boolean isPassed() {
        return reason == null;
    }

    public boolean isRejected() {
        return reason != null;
    }

    public Optional<RejectionReason> reasonOptional() {
        return Optional.ofNullable(reason);
    }
}
