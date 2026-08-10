package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult.ItemRejection;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.BatchMapper;
import uz.hamkorbank.commhub.application.port.in.SubmitBatch;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Accepts a batch and the chunks of its items (FR-1.6, FR-3.1, PU-10).
 *
 * <p>The header makes the batch visible immediately, with a progress of zero; items may follow in as
 * many chunks as the source system needs, and the announced total grows with them.
 *
 * <p>Every item is expanded into an ordinary {@link SubmitMessage} command, so a batch item goes
 * through the identical pipeline — dedup, template, validation, filters, quotas, routing — as a
 * single message. Item failures are reported per item and never fail the chunk (FR-1.4).
 */
@Service
public class SubmitBatchService implements SubmitBatch {

    private final ClockPort clock;
    private final BatchRepository batches;
    private final StreamRepository streams;
    private final SubmitMessage submitMessage;
    private final BatchMapper mapper;

    public SubmitBatchService(
            ClockPort clock,
            BatchRepository batches,
            StreamRepository streams,
            SubmitMessage submitMessage,
            BatchMapper mapper) {
        this.clock = Guard.notNull(clock, "clock");
        this.batches = Guard.notNull(batches, "batches");
        this.streams = Guard.notNull(streams, "streams");
        this.submitMessage = Guard.notNull(submitMessage, "submitMessage");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public BatchAcceptedResult create(CreateBatchCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        Stream stream = streams.findById(command.streamId())
                .orElseThrow(() -> NotFoundException.of("stream", command.streamId()));
        Guard.isTrue(stream.isAcceptingTraffic(), "stream %s is %s".formatted(stream.id(), stream.status()));
        Batch batch = Batch.accept(
                command.batchIdOptional().orElseGet(BatchId::newId),
                command.streamId(),
                command.channel(),
                command.expectedTotal(),
                command.timingOptional().orElseGet(Timing::immediate),
                now);
        batch.applyItemDefaults(new Batch.ItemDefaults(command.trafficClass(), command.template(), command.test()));
        stream.touch(now);
        streams.save(stream);
        return mapper.toAcceptedResult(batches.save(batch));
    }

    @Override
    @Transactional
    public BatchItemsResult addItems(AddBatchItemsCommand command) {
        Guard.notNull(command, "command");
        Batch batch =
                batches.findById(command.batchId()).orElseThrow(() -> NotFoundException.of("batch", command.batchId()));
        Guard.isTrue(!batch.status().isTerminal(), "cannot add items to a batch in status " + batch.status());
        batch.addItems(command.items().size());
        if (batch.status() == BatchStatus.ACCEPTED) {
            batch.startProcessing();
        }

        List<ItemRejection> rejections = new ArrayList<>();
        long accepted = 0L;
        long duplicates = 0L;
        for (AddBatchItemsCommand.Item item : command.items()) {
            SubmitMessageResult result = submitItem(command, batch, item);
            if (result.isAccepted()) {
                accepted++;
            } else if (result.status() == MessageStatus.DUPLICATE) {
                duplicates++;
            } else {
                rejections.add(new ItemRejection(
                        item.externalMessageId(),
                        result.reasonOptional().orElse(RejectionReason.VALIDATION_FAILED),
                        result.detail()));
            }
        }
        batch.registerProcessed(duplicates + rejections.size());
        batches.save(batch);
        return new BatchItemsResult(
                batch.id(), accepted, duplicates, rejections, mapper.toProgressDto(batch.progress()));
    }

    /**
     * Submits one item; a malformed item is reported as its own rejection and never fails the chunk.
     */
    private SubmitMessageResult submitItem(AddBatchItemsCommand command, Batch batch, AddBatchItemsCommand.Item item) {
        try {
            return submitMessage.submit(commandFor(command, batch, item));
        } catch (DomainValidationException e) {
            return SubmitMessageResult.rejected(null, RejectionReason.VALIDATION_FAILED, e.getMessage());
        }
    }

    /**
     * Expands one item into a submission, inheriting the header of the batch (FR-1.6).
     *
     * <p>The traffic class, the TEST flag and the template come from the header the batch was accepted
     * with. They used to be dropped here — {@code POST /api/v1/batches} accepts all three (§8.2) and the
     * items went out as ordinary, non-test messages of the stream's default class, whatever the caller
     * had asked for.
     *
     * <p>{@code pinnedProvider} stays null in any case: the invariant of {@code Delivery} allows pinning
     * only for a test send by an administrator, and choosing the provider is the Hub's job (FR-2.2).
     */
    private static SubmitMessageCommand commandFor(
            AddBatchItemsCommand command, Batch batch, AddBatchItemsCommand.Item item) {
        Batch.ItemDefaults defaults = batch.itemDefaults();
        return new SubmitMessageCommand(
                command.streamId(),
                item.externalMessageId(),
                batch.id(),
                item.recipient(),
                item.contents(),
                item.channelPlanOptional().orElseGet(() -> ChannelPlan.explicitChannel(batch.channel())),
                item.templateOptional().orElseGet(() -> defaults.template()),
                new SubmitMessageCommand.Delivery(
                        defaults.trafficClass(), null, batch.timing(), null, null, defaults.test(), null));
    }
}
