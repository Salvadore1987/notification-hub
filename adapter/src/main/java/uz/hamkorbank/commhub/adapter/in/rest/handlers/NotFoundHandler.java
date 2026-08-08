package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Renders the one business exception the application layer raises as a 404 (IR-01).
 *
 * <p>A message that has not been accepted yet and a message that never existed look the same from
 * outside, and that is intended: a source system polling right after a submission gets 404 until the
 * accepting transaction commits, and retries.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class NotFoundHandler {

    private final ProblemFactory problems;

    public NotFoundHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handle(NotFoundException exception) {
        return ResponseEntity.status(ProblemType.NOT_FOUND.status())
                .body(problems.of(ProblemType.NOT_FOUND, exception.getMessage()));
    }
}
