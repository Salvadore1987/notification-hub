package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Turns "the caller sent something the contract does not allow" into 400 {@code VALIDATION_FAILED}
 * (IR-01).
 *
 * <p>Everything here is the caller's to fix, and the answer says which field: an integration that is
 * told only "400 Bad Request" is debugged by guessing.
 *
 * <p>{@link DomainValidationException} lands here too. It reaches the transport when a value object
 * refuses a value the codec passed on — a text longer than a message may carry, an address the channel
 * cannot use — and to the caller that is the same class of mistake as a missing field.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ContractViolationHandler {

    private final ProblemFactory problems;

    public ContractViolationHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(InboundContractException.class)
    public ResponseEntity<ProblemDetail> handle(InboundContractException exception) {
        return badRequest(problems.ofField(ProblemType.VALIDATION_FAILED, exception.getMessage(), exception.field()));
    }

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ProblemDetail> handle(DomainValidationException exception) {
        return badRequest(problems.of(ProblemType.VALIDATION_FAILED, exception.getMessage()));
    }

    /** A body that is not JSON at all, or a JSON that cannot be read as the declared type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handle(HttpMessageNotReadableException exception) {
        return badRequest(problems.ofField(ProblemType.VALIDATION_FAILED, "the request body is unreadable", "body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handle(MissingServletRequestParameterException exception) {
        return badRequest(problems.ofField(ProblemType.VALIDATION_FAILED, "is required", exception.getParameterName()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handle(HandlerMethodValidationException exception) {
        return badRequest(problems.of(ProblemType.VALIDATION_FAILED, "the request parameters are invalid"));
    }

    /**
     * A guard of the application layer refused the command.
     *
     * <p>{@code IllegalArgumentException} is what {@code Guard} raises for a precondition of a use case
     * — "cannot add items to a batch in status STOPPED" — which is the caller's mistake as much as a
     * malformed field is.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handle(IllegalArgumentException exception) {
        return badRequest(problems.of(ProblemType.VALIDATION_FAILED, exception.getMessage()));
    }

    private static ResponseEntity<ProblemDetail> badRequest(ProblemDetail problem) {
        return ResponseEntity.status(ProblemType.VALIDATION_FAILED.status()).body(problem);
    }
}
