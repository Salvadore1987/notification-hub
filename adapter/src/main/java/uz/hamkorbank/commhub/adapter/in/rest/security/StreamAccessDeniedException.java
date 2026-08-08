package uz.hamkorbank.commhub.adapter.in.rest.security;

/**
 * The caller is authenticated but not entitled to the stream it named (SEC-01).
 *
 * <p>Carries the stream so the log line and the audit entry can name it; the message rendered to the
 * caller does not repeat it, for the same reason the callback endpoint answers a bare 403 — a refusal is
 * not a place to describe the configuration behind it.
 */
public class StreamAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String streamId;

    public StreamAccessDeniedException(String streamId) {
        super("caller is not entitled to stream " + streamId);
        this.streamId = streamId;
    }

    public String streamId() {
        return streamId;
    }
}
