package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.RateLimitExceededException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Renders an exceeded stream rate as 429 with {@code Retry-After} (IR-02).
 *
 * <p>The header is the point of the answer: without it every client backs off by guessing, and the
 * ones that guess wrong keep the limiter saturated.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class RateLimitHandler {

    private final ProblemFactory problems;

    public RateLimitHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handle(RateLimitExceededException exception) {
        ProblemDetail problem = problems.of(
                ProblemType.RATE_LIMITED,
                "stream %s is over its configured request rate".formatted(exception.streamId()));
        return ResponseEntity.status(ProblemType.RATE_LIMITED.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(problem);
    }
}
