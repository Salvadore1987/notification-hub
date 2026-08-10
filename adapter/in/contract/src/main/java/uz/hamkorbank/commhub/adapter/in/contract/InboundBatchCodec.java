package uz.hamkorbank.commhub.adapter.in.contract;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.contract.dto.BatchItemPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.BatchItemsPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.CreateBatchPayload;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapper;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;
import uz.hamkorbank.commhub.domain.exception.DomainException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads the batch documents of §8.2 into their commands (FR-1.6, AR-06).
 *
 * <p>Unlike the message root, both batch documents fit into a record, so Jackson binds them and this
 * class only translates the bound payload into a command.
 */
@Component
public class InboundBatchCodec {

    /**
     * Largest chunk one request may carry (§8.2 "до N=10 000 за запрос").
     *
     * <p>Enforced here rather than left to the use case: a chunk is expanded item by item into full
     * submissions inside one transaction, so the bound is what keeps that transaction — and the
     * request that waits for it — finite.
     */
    public static final int MAX_ITEMS_PER_CHUNK = 10_000;

    private final InboundJson json;
    private final InboundPayloadMapper mapper;

    public InboundBatchCodec(InboundJson json, InboundPayloadMapper mapper) {
        this.json = Guard.notNull(json, "json");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** {@code POST /batches}: the header of a batch send. */
    public CreateBatchCommand readHeader(String document) {
        return toCommand(json.readValue(document, CreateBatchPayload.class));
    }

    /** {@code POST /batches/{id}/items}: one chunk of items for a batch that already exists. */
    public AddBatchItemsCommand readItems(String document, BatchId batchId, StreamId streamId) {
        Guard.notNull(batchId, "batchId");
        Guard.notNull(streamId, "streamId");
        BatchItemsPayload payload = json.readValue(document, BatchItemsPayload.class);
        List<BatchItemPayload> items = payload == null || payload.items() == null ? List.of() : payload.items();
        if (items.isEmpty()) {
            throw InboundContractException.missing("items");
        }
        if (items.size() > MAX_ITEMS_PER_CHUNK) {
            throw InboundContractException.invalid(
                    "items",
                    "a chunk carries at most %d items, received %d".formatted(MAX_ITEMS_PER_CHUNK, items.size()));
        }
        List<AddBatchItemsCommand.Item> mapped = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            mapped.add(mapper.toBatchItem(items.get(index), "items[" + index + "]"));
        }
        return new AddBatchItemsCommand(batchId, streamId, mapped);
    }

    /** Translates an already-bound header, used by the batch-control consumer as well (§8.1). */
    public CreateBatchCommand toCommand(CreateBatchPayload payload) {
        if (payload == null) {
            throw InboundContractException.missing("body");
        }
        if (InboundPayloadMapper.blank(payload.streamId())) {
            throw InboundContractException.missing("streamId");
        }
        Channel channel = InboundPayloadMapper.enumValue(Channel.class, payload.channel(), "channel");
        TrafficClass trafficClass = InboundPayloadMapper.blank(payload.trafficClass())
                ? null
                : InboundPayloadMapper.enumValue(TrafficClass.class, payload.trafficClass(), "trafficClass");
        long expectedTotal = payload.expectedTotal() == null ? 0L : payload.expectedTotal();
        if (expectedTotal < 0) {
            throw InboundContractException.invalid("expectedTotal", "must not be negative");
        }
        try {
            return new CreateBatchCommand(
                    InboundPayloadMapper.blank(payload.batchId())
                            ? null
                            : BatchId.fromString(payload.batchId().trim()),
                    StreamId.of(payload.streamId().trim()),
                    channel,
                    trafficClass,
                    mapper.toTiming(payload.timing(), "timing"),
                    mapper.toTemplate(payload.template(), "template"),
                    expectedTotal,
                    Boolean.TRUE.equals(payload.test()));
        } catch (DomainException | IllegalArgumentException e) {
            throw InboundContractException.invalid("body", e.getMessage(), e);
        }
    }
}
