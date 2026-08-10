package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A batch send managed as a single entity (§6.1, FR-1.6, FR-3.1, FR-3.2).
 *
 * <p>Visible from the moment its header is accepted; items may still arrive in chunks, which is why
 * {@link #addItems(long)} can grow the total after acceptance (FR-1.6).
 */
public final class Batch extends AggregateRoot<BatchId> {

    private final StreamId streamId;
    private final Channel channel;
    private final Timing timing;
    private final Instant createdAt;

    private BatchStatus status;
    private long total;
    private long processed;
    private long sent;
    private long delivered;
    private long failed;
    private Money costEstimate;

    private Batch(BatchId id, StreamId streamId, Channel channel, long total, Timing timing, Instant createdAt) {
        super(id);
        this.streamId = Guard.notNull(streamId, "Batch.streamId");
        this.channel = Guard.notNull(channel, "Batch.channel");
        this.total = Guard.notNegative(total, "Batch.total");
        this.timing = Guard.notNull(timing, "Batch.timing");
        this.createdAt = Guard.notNull(createdAt, "Batch.createdAt");
        this.status = BatchStatus.ACCEPTED;
    }

    private Batch(Rehydration source) {
        this(
                Guard.notNull(source, "source").id,
                source.streamId,
                source.channel,
                Guard.notNegative(source.total, "Batch.total"),
                source.timing,
                source.createdAt);
        this.status = Guard.notNull(source.status, "Batch.status");
        this.processed = Guard.notNegative(source.processed, "Batch.processed");
        this.sent = Guard.notNegative(source.sent, "Batch.sent");
        this.delivered = Guard.notNegative(source.delivered, "Batch.delivered");
        this.failed = Guard.notNegative(source.failed, "Batch.failed");
        this.costEstimate = source.costEstimate;
    }

    /** Accepts a batch header; {@code total} may be 0 when items are uploaded afterwards (FR-1.6). */
    public static Batch accept(
            BatchId id, StreamId streamId, Channel channel, long total, Timing timing, Instant createdAt) {
        return new Batch(id, streamId, channel, total, timing, createdAt);
    }

    /**
     * Starts the reconstitution of a batch read back from storage (§10.1 {@code batch}).
     *
     * <p>The stored status is restored as it is: replaying the transition table over a batch that is
     * already {@code STOPPED} would reject the very state the database holds.
     */
    public static Rehydration rehydrate(
            BatchId id, StreamId streamId, Channel channel, Timing timing, Instant createdAt) {
        return new Rehydration(id, streamId, channel, timing, createdAt);
    }

    /** Registers another chunk of uploaded items (FR-1.6). */
    public void addItems(long itemCount) {
        Guard.notNegative(itemCount, "itemCount");
        Guard.isTrue(!status.isTerminal(), "cannot add items to a batch in status " + status);
        this.total += itemCount;
    }

    public void startProcessing() {
        transitionTo(BatchStatus.PROCESSING);
    }

    /** Pauses the batch; in-flight messages finish, nothing new is dispatched (FR-3.2). */
    public void pause() {
        transitionTo(BatchStatus.PAUSED);
    }

    public void resume() {
        transitionTo(BatchStatus.PROCESSING);
    }

    /** Stops the batch; remaining messages are cancelled (FR-3.2). */
    public void stop() {
        transitionTo(BatchStatus.STOPPED);
    }

    public void complete() {
        transitionTo(BatchStatus.COMPLETED);
    }

    /** Registers messages that left the pipeline with any terminal or provider-side status. */
    public void registerProcessed(long count) {
        this.processed = increase(processed, count, "processed");
    }

    public void registerSent(long count) {
        this.sent = increase(sent, count, "sent");
    }

    public void registerDelivered(long count) {
        this.delivered = increase(delivered, count, "delivered");
    }

    public void registerFailed(long count) {
        this.failed = increase(failed, count, "failed");
    }

    /**
     * Applies a counter change computed from one message's transition (FR-3.1, FR-3.3, ADR-0040).
     *
     * <p>Components may be negative: a DLQ retry takes an item back out of {@code failed} and out of
     * {@code processed}, because the message is in flight again. Counters are floored at zero here and
     * in the SQL that persists them — the {@code CHECK} constraint would otherwise refuse the write, and
     * a counter that went negative would be a worse lie than one that stopped at zero.
     */
    public void apply(Delta delta) {
        Guard.notNull(delta, "delta");
        this.processed = floorAtZero(processed + delta.processed());
        this.sent = floorAtZero(sent + delta.sent());
        this.delivered = floorAtZero(delivered + delta.delivered());
        this.failed = floorAtZero(failed + delta.failed());
    }

    private static long floorAtZero(long value) {
        return Math.max(0L, value);
    }

    /**
     * How much one message's transition changes the counters of its batch (ADR-0040).
     *
     * <p>Computed as the difference between what the message contributed before the change and what it
     * contributes after it, which is what makes a repeated provider report cost nothing and a DLQ retry
     * subtract by itself, without anybody having to remember either case.
     */
    public record Delta(long processed, long sent, long delivered, long failed) {

        private static final Delta NONE = new Delta(0, 0, 0, 0);

        public static Delta none() {
            return NONE;
        }

        public boolean isEmpty() {
            return processed == 0 && sent == 0 && delivered == 0 && failed == 0;
        }
    }

    /** Expected cost of the batch by provider tariffs and computed segments (FR-6.2). */
    public void applyCostEstimate(Money estimate) {
        this.costEstimate = Guard.notNull(estimate, "estimate");
    }

    /** Whether every accepted item has been processed. */
    public boolean isFullyProcessed() {
        return total > 0 && processed >= total;
    }

    public Progress progress() {
        return new Progress(total, processed, sent, delivered, failed);
    }

    public StreamId streamId() {
        return streamId;
    }

    public Channel channel() {
        return channel;
    }

    public Timing timing() {
        return timing;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public BatchStatus status() {
        return status;
    }

    public long total() {
        return total;
    }

    public Optional<Money> costEstimate() {
        return Optional.ofNullable(costEstimate);
    }

    /** Whether new messages of this batch may currently be dispatched (FR-3.2). */
    public boolean isDispatchable() {
        return status == BatchStatus.ACCEPTED || status == BatchStatus.PROCESSING;
    }

    private void transitionTo(BatchStatus next) {
        if (!status.canTransitionTo(next)) {
            throw InvalidStatusTransitionException.of("Batch", status, next);
        }
        this.status = next;
    }

    /** Collects the stored state of a batch before handing it back to the application (§10.1). */
    public static final class Rehydration {

        private final BatchId id;
        private final StreamId streamId;
        private final Channel channel;
        private final Timing timing;
        private final Instant createdAt;

        private BatchStatus status = BatchStatus.ACCEPTED;
        private long total;
        private long processed;
        private long sent;
        private long delivered;
        private long failed;
        private Money costEstimate;

        private Rehydration(BatchId id, StreamId streamId, Channel channel, Timing timing, Instant createdAt) {
            this.id = id;
            this.streamId = streamId;
            this.channel = channel;
            this.timing = timing;
            this.createdAt = createdAt;
        }

        public Rehydration status(BatchStatus currentStatus) {
            this.status = currentStatus;
            return this;
        }

        /** Progress counters as they were last persisted (FR-3.1). */
        public Rehydration progress(long totalItems, long processedItems, long sentItems, long deliveredItems) {
            this.total = totalItems;
            this.processed = processedItems;
            this.sent = sentItems;
            this.delivered = deliveredItems;
            return this;
        }

        public Rehydration failed(long failedItems) {
            this.failed = failedItems;
            return this;
        }

        public Rehydration costEstimate(Money estimate) {
            this.costEstimate = estimate;
            return this;
        }

        public Batch build() {
            return new Batch(this);
        }
    }

    private long increase(long current, long count, String counter) {
        Guard.notNegative(count, counter);
        return current + count;
    }

    /**
     * Progress snapshot shown in the admin panel (FR-3.1).
     *
     * @param total accepted items
     * @param processed items that reached a terminal message status
     * @param sent items handed over to a provider
     * @param delivered items confirmed as delivered
     * @param failed items that ended in {@code FAILED}/{@code UNDELIVERED}
     */
    public record Progress(long total, long processed, long sent, long delivered, long failed) {

        public Progress {
            Guard.notNegative(total, "Progress.total");
            Guard.notNegative(processed, "Progress.processed");
            Guard.notNegative(sent, "Progress.sent");
            Guard.notNegative(delivered, "Progress.delivered");
            Guard.notNegative(failed, "Progress.failed");
        }

        public long remaining() {
            return Math.max(0L, total - processed);
        }

        /** Whether every accepted item has been processed and the batch may be closed (FR-3.1). */
        public boolean isComplete() {
            return total > 0 && processed >= total;
        }

        /** Completion in percent, 0 for an empty batch. */
        public double completionPercent() {
            return total == 0L ? 0.0d : Math.min(100.0d, processed * 100.0d / total);
        }

        /** Delivery rate over the processed items, used by monitoring and alerting (FR-6.1, OBS-04). */
        public double deliveryRate() {
            return processed == 0L ? 0.0d : (double) delivered / processed;
        }
    }
}
