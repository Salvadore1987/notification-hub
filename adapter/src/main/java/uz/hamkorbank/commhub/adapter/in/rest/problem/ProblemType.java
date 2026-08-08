package uz.hamkorbank.commhub.adapter.in.rest.problem;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;

/**
 * The catalogue of machine-readable error codes of IR-01 and the HTTP status each maps onto.
 *
 * <p>An enum rather than loose strings, because the code — not the status — is what a source system
 * branches on: several reasons share a status, and 429 for an exhausted quota means something else
 * than 429 for a rate limit. The names mirror {@link RejectionReason} wherever the specification uses
 * the same word, so the code a caller sees over REST is the reason the pipeline recorded.
 */
public enum ProblemType {

    /** Payload, address or reference did not pass validation (FR-1.4). */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    /** The submission repeats one inside the dedup window; nothing was sent again (FR-1.5). */
    DUPLICATE(HttpStatus.CONFLICT, "Duplicate submission"),
    /** The source stream is suspended (FR-1.3). */
    STREAM_SUSPENDED(HttpStatus.CONFLICT, "Stream suspended"),
    /** Count or cost quota of the stream, channel or provider is exhausted (FR-2.6). */
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Quota exceeded"),
    /** No published template version for the requested code and locale (FR-4.1). */
    TEMPLATE_NOT_PUBLISHED(HttpStatus.UNPROCESSABLE_CONTENT, "Template not published"),
    /** A merge field has no value in strict mode (FR-4.3). */
    TEMPLATE_VARIABLE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Template variable missing"),
    /** The recipient is on the suppression list (FR-5.1). */
    SUPPRESSED(HttpStatus.UNPROCESSABLE_CONTENT, "Recipient suppressed"),
    /** The recipient opted out of this kind of traffic (FR-5.2). */
    OPT_OUT(HttpStatus.UNPROCESSABLE_CONTENT, "Recipient opted out"),
    /** Inside quiet hours and the configured behaviour is to reject (FR-5.3). */
    QUIET_HOURS(HttpStatus.UNPROCESSABLE_CONTENT, "Quiet hours"),
    /** The frequency cap of the recipient is reached (FR-5.4). */
    FREQUENCY_CAPPED(HttpStatus.TOO_MANY_REQUESTS, "Frequency cap reached"),
    /** Content contains a full card number (SEC-05). */
    PAN_DETECTED(HttpStatus.UNPROCESSABLE_CONTENT, "Card number in content"),
    /** No enabled channel or provider can serve the message (FR-2.2). */
    NO_ROUTE_AVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "No route available"),
    /** Sending is stopped by the global kill switch (FR-3.2). */
    KILL_SWITCH(HttpStatus.SERVICE_UNAVAILABLE, "Sending stopped"),
    /** The batch or stream this message belongs to was stopped (FR-3.2). */
    SEND_STOPPED(HttpStatus.CONFLICT, "Send stopped"),
    /** TTL elapsed before the message could be handed to a provider (FR-3.4). */
    TTL_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Message expired"),
    /** The provider refused the message permanently (§18.1, §18.2). */
    PROVIDER_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "Provider rejected the message"),
    /** Retries and fallbacks are exhausted (PR-01). */
    ATTEMPTS_EXHAUSTED(HttpStatus.UNPROCESSABLE_CONTENT, "Delivery attempts exhausted"),

    /** Nothing matches the identifiers in the request. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    /** The aggregate is not in a state that allows the requested action (FR-3.2). */
    CONFLICT(HttpStatus.CONFLICT, "Conflicting state"),
    /** The stream sends faster than its configured rate; retry after the given delay (IR-02). */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),
    /** The Hub failed to process the request; nothing was accepted. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    /** Base of the {@code type} URI; the documentation of every code lives under it (RFC 9457). */
    public static final String TYPE_PREFIX = "https://docs.hamkorbank.uz/commhub/problems/";

    private final HttpStatus status;
    private final String title;

    ProblemType(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    /** The reason the pipeline recorded → the code the caller is told (IR-01). */
    public static ProblemType of(RejectionReason reason) {
        return switch (reason) {
            case VALIDATION_FAILED -> VALIDATION_FAILED;
            case DUPLICATE_SUBMISSION -> DUPLICATE;
            case SUPPRESSED -> SUPPRESSED;
            case OPT_OUT -> OPT_OUT;
            case QUIET_HOURS -> QUIET_HOURS;
            case FREQUENCY_CAPPED -> FREQUENCY_CAPPED;
            case QUOTA_EXCEEDED -> QUOTA_EXCEEDED;
            case STREAM_SUSPENDED -> STREAM_SUSPENDED;
            case TEMPLATE_NOT_PUBLISHED -> TEMPLATE_NOT_PUBLISHED;
            case TEMPLATE_VARIABLE_MISSING -> TEMPLATE_VARIABLE_MISSING;
            case NO_ROUTE_AVAILABLE -> NO_ROUTE_AVAILABLE;
            case PAN_DETECTED -> PAN_DETECTED;
            case TTL_EXPIRED -> TTL_EXPIRED;
            case SEND_STOPPED -> SEND_STOPPED;
            case KILL_SWITCH -> KILL_SWITCH;
            case PROVIDER_REJECTED -> PROVIDER_REJECTED;
            case ATTEMPTS_EXHAUSTED -> ATTEMPTS_EXHAUSTED;
        };
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** {@code type} of the problem document; stable and dereferenceable (RFC 9457). */
    public URI type() {
        return URI.create(TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
