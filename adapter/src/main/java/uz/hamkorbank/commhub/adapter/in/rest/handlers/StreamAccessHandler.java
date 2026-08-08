package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.adapter.in.rest.security.StreamAccessDeniedException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A caller asked for a stream that is not theirs (SEC-01).
 *
 * <p>403 with the code of IR-01 and nothing else: the response says the request was refused, the log
 * line says which stream and which caller. That split is deliberate — the caller already knows what it
 * asked for, and the detail belongs to whoever has to fix the token, not to whoever presented it.
 *
 * <p>Logged at WARN rather than INFO: with SEC-01 deployed, this happens when a token was issued with
 * the wrong stream claim or when a system is submitting for a stream that was reassigned, and both are
 * things somebody has to act on.
 */
@RestControllerAdvice
public class StreamAccessHandler {

    private static final Logger LOG = LoggerFactory.getLogger(StreamAccessHandler.class);

    private final ProblemFactory problems;

    public StreamAccessHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(StreamAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> onStreamAccessDenied(StreamAccessDeniedException e) {
        LOG.warn("Refused access to stream {}: the caller is not entitled to it (SEC-01)", e.streamId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problems.of(ProblemType.FORBIDDEN, "the caller is not entitled to this stream"));
    }
}
