package uz.hamkorbank.commhub.adapter.in.callback.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.hamkorbank.commhub.adapter.in.callback.CallbackAuthenticationException;
import uz.hamkorbank.commhub.adapter.in.callback.ProviderCallbackController;

/**
 * Answers a callback that failed authentication with a bare 403 (SEC-07).
 *
 * <p>Scoped to the callback controller so it cannot affect the source-system API, and deliberately
 * uninformative: the reason is in the Hub's log, not in the response. Telling a caller whether it was
 * the address or the secret that was rejected turns the endpoint into an oracle.
 */
@RestControllerAdvice(assignableTypes = ProviderCallbackController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CallbackAuthenticationHandler {

    @ExceptionHandler(CallbackAuthenticationException.class)
    public ResponseEntity<ProblemDetail> handle(CallbackAuthenticationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "callback refused");
        problem.setTitle("Forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
