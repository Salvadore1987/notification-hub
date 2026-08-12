package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Renders the two ways a request can contradict stored state as 409 (FR-3.2, FR-2.1).
 *
 * <p>Pausing a batch that has already been stopped is not a malformed request — the request is
 * perfectly well formed, the batch is simply past the point where it applies. 409 with the transition
 * spelled out lets a caller reconcile its own view instead of retrying.
 *
 * <p>The same is true of a configuration edit that contradicts what is stored — a stream registered
 * twice, a provider still named in a fallback order, a template with nothing published in the locale
 * that was asked for. The application layer raises {@code ConfigurationConflictException} for those and
 * has always documented them as 409; without a handler here they reached
 * {@link UnexpectedFailureHandler} and came back as <strong>500</strong>, which tells the panel "the
 * Hub is broken" about a mistake only the operator can fix.
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
        return conflict(exception.getMessage());
    }

    @ExceptionHandler(ConfigurationConflictException.class)
    public ResponseEntity<ProblemDetail> handle(ConfigurationConflictException exception) {
        return conflict(exception.getMessage());
    }

    private ResponseEntity<ProblemDetail> conflict(String detail) {
        return ResponseEntity.status(ProblemType.CONFLICT.status()).body(problems.of(ProblemType.CONFLICT, detail));
    }
}
