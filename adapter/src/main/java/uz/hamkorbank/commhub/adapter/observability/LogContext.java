package uz.hamkorbank.commhub.adapter.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * The fields every log line of a request carries: message, stream, batch and correlation id (OBS-03).
 *
 * <p>A structured log is only searchable if the identifiers are members of the document rather than
 * words inside a sentence — "which lines belong to this batch" has to be a filter, not a text search —
 * and MDC is what puts them there. What goes in is deliberately short: identifiers the Bank's log
 * platform indexes, never a recipient, never content.
 *
 * <p>Used as a resource so that the context cannot outlive the work it describes:
 *
 * <pre>{@code
 * try (LogContext ignored = LogContext.of(LogContext.STREAM_ID, streamId).with(LogContext.BATCH_ID, batchId)) {
 *     ...
 * }
 * }</pre>
 *
 * <p>Closing restores the previous values instead of clearing them, because these threads are reused —
 * a Kafka listener thread, a virtual thread from the dispatcher pool — and clearing would erase the
 * context of whatever was already running around it.
 */
public final class LogContext implements AutoCloseable {

    /** Trace identifier of the source system, propagated end to end (FR-8.6, OBS-02). */
    public static final String CORRELATION_ID = "correlationId";

    public static final String MESSAGE_ID = "messageId";

    public static final String STREAM_ID = "streamId";

    public static final String BATCH_ID = "batchId";

    /** Traffic class of the message; makes "show me the OTP path" a filter (TC-01). */
    public static final String TRAFFIC_CLASS = "trafficClass";

    public static final String PROVIDER = "provider";

    private final Map<String, String> previous = new LinkedHashMap<>();

    private LogContext() {}

    /** Opens a context with one field; blank values are ignored so nothing writes an empty member. */
    public static LogContext of(String key, String value) {
        return new LogContext().with(key, value);
    }

    /** Opens an empty context, for callers that add their fields conditionally. */
    public static LogContext empty() {
        return new LogContext();
    }

    public LogContext with(String key, String value) {
        if (key == null || value == null || value.isBlank()) {
            return this;
        }
        previous.putIfAbsent(key, MDC.get(key));
        MDC.put(key, value);
        return this;
    }

    /** Same, for identifiers that are value objects; {@code null} simply adds nothing. */
    public LogContext with(String key, Object value) {
        return value == null ? this : with(key, value.toString());
    }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
        previous.clear();
    }
}
