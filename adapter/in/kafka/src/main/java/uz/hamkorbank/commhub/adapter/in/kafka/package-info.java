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
 * <p>There is no rate limit here, unlike on the REST side (IR-02), and that is deliberate: a record is
 * already durable in the topic when the Hub sees it, so there is no caller left to slow down and
 * refusing it would mean losing it or stalling its partition. The pace of this side is set by the
 * consumer configuration — concurrency per class and {@code max-poll-records} — while the limits that
 * count messages rather than requests (the quotas of FR-2.6) are applied inside the pipeline and
 * therefore hold for both transports.
 *
 * <p>Delivery is at-least-once and processing is idempotent by dedup key (FR-1.5, AD-03): a
 * redelivery after a rebalance or a crash costs a lookup, not a second SMS. Records that cannot be
 * read at all go to {@code comm.inbound.parse-error.v1} instead of blocking their partition (IK-04) —
 * see {@code InboundErrorHandlerConfig} for which failures are retried and which are not.
 */
package uz.hamkorbank.commhub.adapter.in.kafka;
