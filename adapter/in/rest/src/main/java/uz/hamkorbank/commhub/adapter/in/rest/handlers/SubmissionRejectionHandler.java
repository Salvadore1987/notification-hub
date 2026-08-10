package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.adapter.in.rest.problem.SubmissionRejectedException;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Renders a refused submission as the {@code problem+json} of IR-01.
 *
 * <p>The status comes from the code, not the other way round: a suppressed recipient and an exhausted
 * quota are both refusals, but one is permanent for this recipient and the other passes when the
 * window rolls over, and a caller can only tell them apart by the code.
 *
 * <p>A duplicate keeps the identifier of the message it repeats in {@code messageId}, so the caller can
 * poll the status of the original instead of resending (FR-1.5).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SubmissionRejectionHandler {

    private final ProblemFactory problems;

    public SubmissionRejectionHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(SubmissionRejectedException.class)
    public ResponseEntity<ProblemDetail> handle(SubmissionRejectedException exception) {
        SubmitMessageResult result = exception.result();
        RejectionReason reason = result.reasonOptional().orElse(RejectionReason.VALIDATION_FAILED);
        ProblemType type = ProblemType.of(reason);
        ProblemDetail problem = problems.of(type, detailOf(result, reason));
        result.messageIdOptional()
                .ifPresent(messageId -> problem.setProperty(ProblemFactory.MESSAGE_ID, messageId.toString()));
        return ResponseEntity.status(type.status()).body(problem);
    }

    private static String detailOf(SubmitMessageResult result, RejectionReason reason) {
        if (result.detail() != null && !result.detail().isBlank()) {
            return result.detail();
        }
        return "the submission was refused: " + reason.name();
    }
}
