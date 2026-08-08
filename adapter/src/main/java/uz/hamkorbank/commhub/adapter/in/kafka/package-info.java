/**
 * Driving adapter: the asynchronous ingress from Kafka, the primary transport of the Hub (§8.1).
 *
 * <p>Four topics, four container factories, four thread pools (IK-01, TC-01). The split is not
 * cosmetic: it is the mechanism by which a bulk send of a million notifications cannot delay an OTP —
 * they are consumed by different threads, committed in different consumer groups, and lag separately
 * in the broker's metrics.
 *
 * <p>The document on the wire is the shared contract of {@code adapter.in.contract}, the same one the
 * REST adapter accepts, so a source system may switch transports without changing what it sends.
 *
 * <p>Delivery is at-least-once and processing is idempotent by dedup key (FR-1.5, AD-03): a
 * redelivery after a rebalance or a crash costs a lookup, not a second SMS. Records that cannot be
 * read at all go to {@code comm.inbound.parse-error.v1} instead of blocking their partition (IK-04) —
 * see {@code InboundErrorHandlerConfig} for which failures are retried and which are not.
 */
package uz.hamkorbank.commhub.adapter.in.kafka;
