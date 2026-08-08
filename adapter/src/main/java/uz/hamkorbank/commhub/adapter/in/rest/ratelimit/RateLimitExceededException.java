package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;

import java.time.Duration;

/**
 * A stream sent faster than its configured rate (IR-02).
 *
 * <p>Carries how long the caller should wait, which becomes the {@code Retry-After} header: an OTP
 * client that is told "later" and not "how much later" will simply retry immediately.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String streamId;
    private final Duration retryAfter;

    public RateLimitExceededException(String streamId, Duration retryAfter) {
        super("stream %s exceeded its request rate".formatted(streamId));
        this.streamId = streamId;
        this.retryAfter = retryAfter;
    }

    public String streamId() {
        return streamId;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** {@code Retry-After} is expressed in whole seconds and never as 0, which reads as "now". */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds();
        return Math.max(1L, retryAfter.minusSeconds(seconds).isZero() ? seconds : seconds + 1);
    }
}
