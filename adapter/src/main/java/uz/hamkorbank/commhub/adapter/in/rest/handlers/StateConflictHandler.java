package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Renders a refused state transition as 409 (FR-3.2).
 *
 * <p>Pausing a batch that has already been stopped is not a malformed request — the request is
 * perfectly well formed, the batch is simply past the point where it applies. 409 with the transition
 * spelled out lets a caller reconcile its own view instead of retrying.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class StateConflictHandler {

    private final ProblemFactory problems;

    public StateConflictHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ProblemDetail> handle(InvalidStatusTransitionException exception) {
        return ResponseEntity.status(ProblemType.CONFLICT.status())
                .body(problems.of(ProblemType.CONFLICT, exception.getMessage()));
    }
}
