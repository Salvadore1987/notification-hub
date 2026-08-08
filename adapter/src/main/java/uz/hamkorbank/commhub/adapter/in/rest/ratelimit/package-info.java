/**
 * Rate limiting of the synchronous API per source stream (IR-02).
 *
 * <p>This is transport protection — how often a caller may knock — and not the business quotas of
 * FR-2.6, which count messages and cost against a stream, a channel and a provider inside the
 * pipeline. The two answer with different codes for exactly that reason: {@code RATE_LIMITED} means
 * "slow down", {@code QUOTA_EXCEEDED} means "your budget is spent".
 *
 * <p><strong>Why the Kafka ingress has no counterpart.</strong> A rate limit is a backpressure signal,
 * and over REST there is someone to send it to: the caller is still holding the connection and can be
 * told to come back in {@code Retry-After} seconds. A Kafka record is already durable in the topic by
 * the time the Hub sees it, and the consumer decides for itself when to poll — there is nobody left to
 * slow down. Refusing such a record could only mean dropping it or not committing its offset, which
 * blocks the partition behind it; both are worse than simply consuming it a little later. What
 * throttles that side is the consumer configuration itself — the concurrency per traffic class and
 * {@code max-poll-records} of {@code commhub.kafka.inbound} — and lag is an acceptable state for a
 * queue in a way that a waiting HTTP caller is not.
 *
 * <p>So a message that arrives over Kafka is not unprotected, it is protected further in: both
 * transports run the same {@code SubmitMessage}, which applies the stream status (FR-1.3), the kill
 * switch (FR-3.2) and the quotas of FR-2.6 per message. Only the transport-level knock rate is
 * REST-only, and only because only REST can express it.
 *
 * <p>The check sits in the controllers rather than in a filter because the stream is named inside the
 * IK-03 body: a filter would have to buffer and parse the request to learn whom to charge. On the one
 * path where parsing is genuinely expensive — a chunk of up to 10 000 batch items — the stream arrives
 * as a query parameter and the limit is applied before the body is read.
 */
package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;
