package uz.hamkorbank.commhub.adapter.in.admin.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A caller who is somebody, asking for something their roles do not cover (SEC-02, SEC-03).
 *
 * <p>This is what a {@code @PreAuthorize} of {@code AdminAuthority} refuses. Method security throws
 * inside the dispatcher rather than in the filter chain, so Spring's own translation to 403 never gets
 * the chance and, without an advice, the refusal reaches {@code UnexpectedFailureHandler} and comes back
 * as 500 — a permissions problem reported as a broken Hub, which sends an operator to the wrong person.
 * Nobody noticed while every expression began with {@code @adminAccess.open()}: no denial ever happened.
 *
 * <p>403 and not 401: the chain has already established who the caller is — an unauthenticated one does
 * not reach a controller under {@code /api/admin/v1} (ADR-0037) — so what is missing is an entitlement,
 * not a login. The body carries the code and nothing else; which role would have sufficed is a statement
 * about the Hub's authorisation model and belongs in the log, for whoever administers the SSO groups.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthorizationDeniedHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AuthorizationDeniedHandler.class);

    private final ProblemFactory problems;

    public AuthorizationDeniedHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> onAccessDenied(AccessDeniedException e) {
        LOG.warn(
                "Refused an endpoint to an authenticated caller whose roles do not cover it (SEC-03): {}",
                e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problems.of(ProblemType.FORBIDDEN, "the caller's roles do not cover this endpoint"));
    }
}
