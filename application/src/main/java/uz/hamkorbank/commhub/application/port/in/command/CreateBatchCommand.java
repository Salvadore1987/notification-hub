package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Header of a batch send (FR-1.6, §8.2 {@code POST /batches}).
 *
 * @param batchId identifier proposed by the source system; {@code null} lets the Hub generate one
 * @param expectedTotal announced number of items; may be 0 when items follow in chunks (FR-1.6)
 * @param template template shared by every item; individual items may override it (FR-4.3)
 */
public record CreateBatchCommand(
        BatchId batchId,
        StreamId streamId,
        Channel channel,
        TrafficClass trafficClass,
        Timing timing,
        TemplateRef template,
        long expectedTotal,
        boolean test) {

    public CreateBatchCommand {
        Guard.notNull(streamId, "CreateBatchCommand.streamId");
        Guard.notNull(channel, "CreateBatchCommand.channel");
        Guard.notNegative(expectedTotal, "CreateBatchCommand.expectedTotal");
    }

    public static CreateBatchCommand of(StreamId streamId, Channel channel, long expectedTotal) {
        return new CreateBatchCommand(null, streamId, channel, null, null, null, expectedTotal, false);
    }

    public Optional<BatchId> batchIdOptional() {
        return Optional.ofNullable(batchId);
    }

    public Optional<Timing> timingOptional() {
        return Optional.ofNullable(timing);
    }

    public Optional<TemplateRef> templateOptional() {
        return Optional.ofNullable(template);
    }
}
