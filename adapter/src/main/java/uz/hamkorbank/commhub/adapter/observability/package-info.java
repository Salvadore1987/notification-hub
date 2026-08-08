/**
 * Cross-cutting observability of the adapters: correlation, log context and log masking (OBS-02, OBS-03).
 *
 * <p>Neither a driving nor a driven adapter, which is why it sits beside {@code in} and {@code out}
 * rather than inside one of them. Every entry point uses it — the REST API, the provider callbacks, the
 * Kafka listeners, the schedulers — and it drives nothing and is driven by nothing: a correlation id is
 * not a use case.
 *
 * <p>Three concerns live here:
 *
 * <ul>
 *   <li>{@link uz.hamkorbank.commhub.adapter.observability.CorrelationIdFilter} — the identifier of
 *       FR-8.6 for HTTP: taken from the caller or created, echoed back, put into the MDC and into the
 *       tracing baggage so it leaves the process with the span;
 *   <li>{@link uz.hamkorbank.commhub.adapter.observability.LogContext} — the MDC fields OBS-03 asks for
 *       ({@code messageId}, {@code streamId}, {@code batchId}, {@code correlationId}), opened as a
 *       resource so a pooled thread never inherits the context of the message before it;
 *   <li>{@link uz.hamkorbank.commhub.adapter.observability.LogMasking} and
 *       {@link uz.hamkorbank.commhub.adapter.observability.PiiMaskingJsonCustomizer} — the safety net
 *       under the rule that the Hub masks at the point of writing.
 * </ul>
 *
 * <p>Tracing itself is configuration and not code: the spans of OBS-02 come from Micrometer's own
 * instrumentation of the servlet API, the Kafka clients and the JDBC pool, exported over OTLP by the
 * bridge wired in {@code bootstrap}. What could not be configured is the one thing the Bank's systems
 * care about — that <em>their</em> identifier travels with the trace — and that is this package.
 */
package uz.hamkorbank.commhub.adapter.observability;
