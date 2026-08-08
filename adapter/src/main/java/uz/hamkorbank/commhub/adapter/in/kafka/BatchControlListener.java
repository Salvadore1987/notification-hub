package uz.hamkorbank.commhub.adapter.in.kafka;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.BatchActions;
import uz.hamkorbank.commhub.adapter.in.contract.InboundBatchCodec;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.contract.InboundJson;
import uz.hamkorbank.commhub.adapter.in.contract.dto.BatchCommandPayload;
import uz.hamkorbank.commhub.adapter.observability.LogContext;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.port.in.SubmitBatch;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Driving adapter for {@code comm.inbound.batch-control.v1} (§8.1 IK-01, FR-1.6, FR-3.2).
 *
 * <p>Headers and control commands share one topic keyed by {@code batchId}, so the create of a batch
 * and the pause that may follow it land in the same partition and are seen in that order — a source
 * system cannot pause a batch this instance has not created yet.
 *
 * <p>Items do not come here: they arrive on {@code comm.inbound.notification.v1} with the batch id in
 * the envelope, where the bulk pool processes them without competing with control traffic.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.kafka.inbound", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BatchControlListener {

    private static final Logger LOG = LoggerFactory.getLogger(BatchControlListener.class);

    private final InboundJson json;
    private final InboundBatchCodec codec;
    private final SubmitBatch submitBatch;
    private final BatchActions actions;

    public BatchControlListener(
            InboundJson json, InboundBatchCodec codec, SubmitBatch submitBatch, BatchActions actions) {
        this.json = Guard.notNull(json, "json");
        this.codec = Guard.notNull(codec, "codec");
        this.submitBatch = Guard.notNull(submitBatch, "submitBatch");
        this.actions = Guard.notNull(actions, "actions");
    }

    @KafkaListener(
            topics = "${commhub.kafka.inbound.topics.batch-control:comm.inbound.batch-control.v1}",
            containerFactory = KafkaConsumerConfig.BATCH_CONTROL_FACTORY)
    public void onCommand(String document) {
        BatchCommandPayload payload = json.readValue(document, BatchCommandPayload.class);
        if (payload == null || payload.action() == null || payload.action().isBlank()) {
            throw InboundContractException.missing("action");
        }
        String action = payload.action().trim().toUpperCase(Locale.ROOT);
        try (LogContext ignored = LogContext.of(LogContext.BATCH_ID, payload.batchId())) {
            apply(action, payload);
            LOG.debug("Applied batch command {} to {}", action, payload.batchId());
        }
    }

    private void apply(String action, BatchCommandPayload payload) {
        switch (action) {
            case BatchCommandPayload.ACTION_CREATE -> create(payload);
            case BatchCommandPayload.ACTION_START -> actions.start().start(commandOf(payload));
            case BatchCommandPayload.ACTION_PAUSE -> actions.pause().pause(commandOf(payload));
            case BatchCommandPayload.ACTION_RESUME -> actions.resume().resume(commandOf(payload));
            case BatchCommandPayload.ACTION_STOP -> actions.stop().stop(commandOf(payload));
            default ->
                throw InboundContractException.invalid(
                        "action", "unknown action %s, expected CREATE|START|PAUSE|RESUME|STOP".formatted(action));
        }
    }

    private void create(BatchCommandPayload payload) {
        if (payload.batch() == null) {
            throw InboundContractException.missing("batch");
        }
        BatchAcceptedResult result = submitBatch.create(codec.toCommand(payload.batch()));
        LOG.debug("Accepted batch {} with {} announced items", result.batchId(), result.total());
    }

    private static BatchActionCommand commandOf(BatchCommandPayload payload) {
        if (payload.batchId() == null || payload.batchId().isBlank()) {
            throw InboundContractException.missing("batchId");
        }
        return new BatchActionCommand(batchId(payload.batchId()), actorOf(payload.actor()), payload.reason());
    }

    private static BatchId batchId(String raw) {
        try {
            return BatchId.fromString(raw.trim());
        } catch (RuntimeException e) {
            throw InboundContractException.invalid("batchId", "is not a UUID", e);
        }
    }

    /** Whoever the source system names is recorded as the operator; unnamed is the system (FR-7.3). */
    private static Actor actorOf(String actor) {
        return actor == null || actor.isBlank() ? Actor.system() : Actor.operator(actor.trim());
    }
}
