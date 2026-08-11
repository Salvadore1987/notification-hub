package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemType;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Renders the requests that never reached a controller as the status they are (RFC 9110, IR-01).
 *
 * <p>A wrong method, an unreadable media type, an impossible {@code Accept} and an unknown path are all
 * statements about the request, and every one of them used to arrive at {@link UnexpectedFailureHandler}
 * and come back as <strong>500</strong>. That is the one answer they must not give: 5xx tells a caller
 * "the Hub is broken, retry", and a source system with a retry loop then hammers an endpoint that will
 * refuse it identically for ever, while the Hub logs a stack trace per attempt for a defect that is not
 * its own. It also hides the mistake from whoever made it — a client that mistyped a method learns
 * nothing from "internal error".
 *
 * <p>The headers are the load-bearing part rather than decoration. RFC 9110 §15.5.6 <em>requires</em>
 * {@code Allow} on a 405, and it is what lets a caller correct the call instead of guessing; the same
 * argument gives {@code Accept} to a 415. Both are read off the exception, because Spring already knows
 * what the mapping supports and a hand-written list would drift away from it.
 *
 * <p>Ordered before the catch-all and after the advices that render business verdicts: these exceptions
 * are transport-level and cannot collide with IR-01, so the slot only has to beat {@link Exception}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class UnsupportedRequestHandler {

    private final ProblemFactory problems;

    public UnsupportedRequestHandler(ProblemFactory problems) {
        this.problems = Guard.notNull(problems, "problems");
    }

    /** 405 with {@code Allow}, which RFC 9110 §15.5.6 makes mandatory rather than helpful. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handle(HttpRequestMethodNotSupportedException exception) {
        ResponseEntity.BodyBuilder response = response(ProblemType.METHOD_NOT_ALLOWED);
        if (exception.getSupportedHttpMethods() != null) {
            response.allow(exception.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return response.body(problems.of(
                ProblemType.METHOD_NOT_ALLOWED,
                "method %s is not supported by this endpoint".formatted(exception.getMethod())));
    }

    /** 415, naming what the endpoint does read — the caller usually forgot a {@code Content-Type}. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handle(HttpMediaTypeNotSupportedException exception) {
        ResponseEntity.BodyBuilder response = response(ProblemType.UNSUPPORTED_MEDIA_TYPE);
        if (!exception.getSupportedMediaTypes().isEmpty()) {
            response.header(HttpHeaders.ACCEPT, MediaType.toString(exception.getSupportedMediaTypes()));
        }
        return response.body(problems.of(
                ProblemType.UNSUPPORTED_MEDIA_TYPE,
                "media type %s cannot be read by this endpoint".formatted(exception.getContentType())));
    }

    /**
     * 406 for an {@code Accept} nothing can satisfy.
     *
     * <p>The content type is forced here for a reason that only shows on this one status: the answer is
     * itself a media type the caller just said it does not accept, so leaving it to negotiation loses
     * the body and the whole answer becomes an empty 406.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handle(HttpMediaTypeNotAcceptableException exception) {
        return response(ProblemType.NOT_ACCEPTABLE)
                .body(problems.of(
                        ProblemType.NOT_ACCEPTABLE, "no representation of this endpoint matches the Accept header"));
    }

    /**
     * 404 for a path no mapping owns.
     *
     * <p>Two exceptions for one symptom: a request that matched no mapping falls through to the static
     * resource handler and fails there as {@link NoResourceFoundException}, while
     * {@link NoHandlerFoundException} is what arrives when resource handling is switched off. Which one
     * a deployment produces is a property of the configuration, so both are answered the same.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleUnknownPath(Exception exception) {
        return response(ProblemType.NOT_FOUND).body(problems.of(ProblemType.NOT_FOUND, "no endpoint at this path"));
    }

    private static ResponseEntity.BodyBuilder response(ProblemType type) {
        return ResponseEntity.status(type.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
