package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Last resort: anything not recognised by the other advices becomes a bare 500 (IR-01).
 *
 * <p>The stack trace goes to the log and nothing of it to the caller — an exception message can carry
 * a recipient address, a template body or a provider credential, and a source system is the wrong
 * place for any of them (SEC-03, SEC-04).
 *
 * <p>Ordered last: it catches {@link Exception} and would otherwise take precedence over the advices
 * that know what to do with a specific failure.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class UnexpectedFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(UnexpectedFailureHandler.class);

    private final ProblemFactory problems;

    public UnexpectedFailureHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle(Exception exception) {
        LOG.error("Unhandled failure while serving a source-system request", exception);
        return ResponseEntity.status(ProblemType.INTERNAL_ERROR.status())
                .body(problems.of(ProblemType.INTERNAL_ERROR, "the request could not be processed"));
    }
}
