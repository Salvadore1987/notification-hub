package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pace of the outbox relay (AD-03).
 *
 * @param enabled whether this instance runs the relay; every instance does by default — the claim is
 *     {@code SKIP LOCKED}, so they share the queue instead of duplicating it
 * @param pollIntervalMs delay between two passes when there is nothing to publish; it is the idle
 *     latency a status event pays, so it stays well inside the OTP budget of TC-01
 * @param batchSize events one pass may publish
 * @param maxPassesPerTick how many back-to-back passes a tick may run while the outbox keeps filling
 *     batches; bounds the time one tick can hold the thread when a backlog is draining
 */
@ConfigurationProperties("commhub.outbox.relay")
public record OutboxRelayProperties(Boolean enabled, Long pollIntervalMs, Integer batchSize, Integer maxPassesPerTick) {

    public static final long DEFAULT_POLL_INTERVAL_MS = 500;

    public static final int DEFAULT_BATCH_SIZE = 200;

    public static final int DEFAULT_MAX_PASSES_PER_TICK = 10;

    public OutboxRelayProperties {
        enabled = enabled == null || enabled;
        pollIntervalMs = pollIntervalMs == null ? DEFAULT_POLL_INTERVAL_MS : pollIntervalMs;
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        maxPassesPerTick = maxPassesPerTick == null ? DEFAULT_MAX_PASSES_PER_TICK : maxPassesPerTick;
        if (pollIntervalMs < 1) {
            throw new IllegalArgumentException("commhub.outbox.relay.poll-interval-ms must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("commhub.outbox.relay.batch-size must be positive");
        }
        if (maxPassesPerTick < 1) {
            throw new IllegalArgumentException("commhub.outbox.relay.max-passes-per-tick must be positive");
        }
    }

    public static OutboxRelayProperties defaults() {
        return new OutboxRelayProperties(null, null, null, null);
    }
}
