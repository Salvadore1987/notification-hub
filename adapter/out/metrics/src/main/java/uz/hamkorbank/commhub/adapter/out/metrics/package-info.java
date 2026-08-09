/**
 * Micrometer side of the Hub: the implementation of {@code MetricsPort} and the gauges that watch what
 * no use case reports (OBS-01, OBS-04).
 *
 * <p>Three kinds of thing live here and the split is deliberate:
 *
 * <ul>
 *   <li>{@link uz.hamkorbank.commhub.adapter.out.metrics.MicrometerMetricsAdapter} — what the pipeline
 *       itself reports through the port: accepted, rejected, statuses, provider calls, quotas, PAN
 *       findings. The application layer has neither a logger nor a metrics library, so this is the only
 *       route out for anything a use case wants to say (AR-02);
 *   <li>{@link uz.hamkorbank.commhub.adapter.out.metrics.CircuitBreakerMetrics} — state that belongs to
 *       an adapter and not to a use case: the breakers of PR-01 live in Resilience4j registries, and the
 *       core is not told when one opens because it is not its decision;
 *   <li>{@link uz.hamkorbank.commhub.adapter.out.metrics.BacklogMetrics} — what is not moving. Counters
 *       describe events; a backlog is a state, and it needs measuring rather than counting.
 * </ul>
 *
 * <p>The registry itself is not chosen here. {@code MeterRegistry} is what this package compiles against;
 * whether it publishes to Prometheus is a deployment decision made in {@code bootstrap} (NF-06).
 *
 * <p>Names and tags are in {@link uz.hamkorbank.commhub.adapter.out.metrics.MetricNames}, because the
 * alert rules (OBS-04) and dashboards (OBS-05) shipped with the system are written against them.
 */
package uz.hamkorbank.commhub.adapter.out.metrics;
