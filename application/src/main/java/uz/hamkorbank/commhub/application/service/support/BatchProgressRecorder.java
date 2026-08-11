package uz.hamkorbank.commhub.application.service.support;

import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Keeps the progress counters of a batch in step with its messages (FR-3.1, FR-3.3, ADR-0040).
 *
 * <p>The rule is one line: <em>the delta is what the message contributes now minus what it contributed
 * before</em>. Every case that makes a naive "increment here" wrong falls out of that arithmetic instead
 * of having to be remembered — a provider report delivered twice changes nothing because the
 * contribution is the same both times; a DLQ retry subtracts by itself, because a message back in flight
 * contributes neither {@code failed} nor {@code processed}; {@code SENT_TO_PROVIDER → RETRYING →
 * SENT_TO_PROVIDER} counts {@code sent} once, because that component is read from the history rather
 * than from the current status.
 *
 * <p>Usage is always paired inside one transaction: take {@link #contributionOf(Message)} before the
 * change, call {@link #apply(Message, Contribution)} after it. Splitting the pair across transactions
 * would make the delta a statement about a state nobody saw.
 */
@Component
public class BatchProgressRecorder {

    private final BatchRepository batches;

    public BatchProgressRecorder(BatchRepository batches) {
        this.batches = Guard.notNull(batches, "batches");
    }

    /** What this message currently adds to its batch's counters. */
    public Contribution contributionOf(Message message) {
        if (message == null || message.envelope().batchIdOptional().isEmpty()) {
            return Contribution.NONE;
        }
        return new Contribution(
                message.isTerminalForChannel(),
                everReachedProvider(message),
                message.status() == MessageStatus.DELIVERED,
                message.status() == MessageStatus.FAILED || message.status() == MessageStatus.UNDELIVERED);
    }

    /**
     * Applies the difference between the contribution taken before the change and the one now.
     *
     * <p>Closes the batch when the change was the last one it was waiting for — once per batch rather
     * than once per message, and still through the aggregate's own transition table.
     */
    public void apply(Message message, Contribution before) {
        Guard.notNull(before, "before");
        Optional<BatchId> batchId =
                message == null ? Optional.empty() : message.envelope().batchIdOptional();
        if (batchId.isEmpty()) {
            return;
        }
        Batch.Delta delta = contributionOf(message).minus(before);
        if (delta.isEmpty()) {
            return;
        }
        Batch.Progress progress = batches.applyProgress(batchId.get(), delta);
        // Счётчики уже записаны; закрывать батч без известного прогресса нечем, но и падать здесь
        // нельзя — это стоило бы всего оборота отправки ради строки на карточке.
        if (progress != null && progress.isComplete()) {
            batches.markCompleted(batchId.get());
        }
    }

    /**
     * Whether the message ever reached a provider.
     *
     * <p>Read from the history and not from the current status: a message that was accepted by a
     * provider, got no delivery report and went back to {@code RETRYING} was still sent once, and
     * counting it again on the second acceptance would inflate the card of every batch that saw a
     * reconciliation (SG-03).
     */
    private static boolean everReachedProvider(Message message) {
        return message.statusHistory().stream()
                .map(StatusChange::status)
                .anyMatch(status -> status == MessageStatus.SENT_TO_PROVIDER);
    }

    /**
     * What one message contributes to the four counters of its batch.
     *
     * <p>A pure function of the message's state, which is the whole reason the arithmetic works.
     */
    public record Contribution(boolean processed, boolean sent, boolean delivered, boolean failed) {

        /** The message belongs to no batch, or there is nothing to count. */
        public static final Contribution NONE = new Contribution(false, false, false, false);

        Batch.Delta minus(Contribution before) {
            return new Batch.Delta(
                    difference(processed, before.processed()),
                    difference(sent, before.sent()),
                    difference(delivered, before.delivered()),
                    difference(failed, before.failed()));
        }

        private static long difference(boolean now, boolean before) {
            if (now == before) {
                return 0L;
            }
            return now ? 1L : -1L;
        }
    }
}
