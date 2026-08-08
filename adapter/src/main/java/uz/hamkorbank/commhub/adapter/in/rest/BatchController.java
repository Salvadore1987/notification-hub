package uz.hamkorbank.commhub.adapter.in.rest;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.BatchActions;
import uz.hamkorbank.commhub.adapter.in.contract.InboundBatchCodec;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchAcceptedResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchActionResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchItemsResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchStatusResponse;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapper;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.StreamRateLimiter;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.port.in.GetBatch;
import uz.hamkorbank.commhub.application.port.in.SubmitBatch;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;
import uz.hamkorbank.commhub.application.port.in.query.BatchQuery;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Batch sends of the source systems (§8.2 {@code /batches}, FR-1.6, FR-3.1, FR-3.2).
 *
 * <p>The header comes first and the items follow in chunks, which is what lets a source system upload
 * a million recipients without holding them all in one request — and what makes the batch visible,
 * with progress, from the moment the header is accepted.
 *
 * <p>{@code start} is here for completeness of the contract: a batch whose items simply arrive begins
 * processing with its first chunk, so the action is only needed by a caller that uploads everything
 * first and starts the send afterwards.
 */
@RestController
@RequestMapping(ApiV1.BATCHES)
public class BatchController {

    private static final String ACTION_START = "start";
    private static final String ACTION_PAUSE = "pause";
    private static final String ACTION_RESUME = "resume";
    private static final String ACTION_STOP = "stop";

    private final InboundBatchCodec codec;
    private final SubmitBatch submitBatch;
    private final BatchActions actions;
    private final GetBatch getBatch;
    private final StreamRateLimiter rateLimiter;
    private final RestResponseMapper mapper;

    public BatchController(
            InboundBatchCodec codec,
            SubmitBatch submitBatch,
            BatchActions actions,
            GetBatch getBatch,
            StreamRateLimiter rateLimiter,
            RestResponseMapper mapper) {
        this.codec = Guard.notNull(codec, "codec");
        this.submitBatch = Guard.notNull(submitBatch, "submitBatch");
        this.actions = Guard.notNull(actions, "actions");
        this.getBatch = Guard.notNull(getBatch, "getBatch");
        this.rateLimiter = Guard.notNull(rateLimiter, "rateLimiter");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** {@code POST /api/v1/batches} — accepts the header of a batch (§8.2). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchAcceptedResponse> create(@RequestBody String body) {
        CreateBatchCommand command = codec.readHeader(body);
        rateLimiter.check(command.streamId().value());
        BatchAcceptedResult result = submitBatch.create(command);
        BatchAcceptedResponse response = mapper.toAccepted(result);
        return ResponseEntity.accepted()
                .location(URI.create(ApiV1.BATCHES + "/" + response.batchId()))
                .body(response);
    }

    /**
     * {@code POST /api/v1/batches/{batchId}/items} — uploads one chunk of items (§8.2).
     *
     * <p>Answers 202 even when some items were refused: the accepted ones are on their way and the
     * refusals are listed per item, each with its own reason (FR-1.4).
     */
    @PostMapping(
            path = "/{batchId}/items",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchItemsResponse> addItems(
            @PathVariable String batchId, @RequestParam String streamId, @RequestBody String body) {
        rateLimiter.check(streamId);
        AddBatchItemsCommand command = codec.readItems(body, batchId(batchId), streamId(streamId));
        return ResponseEntity.accepted().body(mapper.toItems(submitBatch.addItems(command)));
    }

    /** {@code POST /api/v1/batches/{batchId}/actions/{action}} — start, pause, resume or stop (FR-3.2). */
    @PostMapping(path = "/{batchId}/actions/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    public BatchActionResponse act(
            @PathVariable String batchId,
            @PathVariable String action,
            @RequestHeader(name = ApiV1.ACTOR_HEADER, required = false) String actor,
            @RequestHeader(name = ApiV1.REASON_HEADER, required = false) String reason) {
        BatchActionCommand command = new BatchActionCommand(batchId(batchId), actorOf(actor), reason);
        return mapper.toAction(apply(action, command));
    }

    /** {@code GET /api/v1/batches/{batchId}} — status and progress of a batch (§8.2, FR-3.1). */
    @GetMapping(path = "/{batchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public BatchStatusResponse byId(@PathVariable String batchId) {
        return mapper.toStatus(getBatch.get(BatchQuery.byId(batchId(batchId))));
    }

    private BatchControlResult apply(String action, BatchActionCommand command) {
        return switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
            case ACTION_START -> actions.start().start(command);
            case ACTION_PAUSE -> actions.pause().pause(command);
            case ACTION_RESUME -> actions.resume().resume(command);
            case ACTION_STOP -> actions.stop().stop(command);
            default ->
                throw InboundContractException.invalid(
                        "action", "unknown action %s, expected start|pause|resume|stop".formatted(action));
        };
    }

    /**
     * Who is asking. Until the OIDC/mTLS identity of SEC-01 reaches this layer, an unattended source
     * system is recorded as the system itself and a named caller as an operator — both end up in the
     * audit log with the same weight (FR-7.3).
     */
    private static Actor actorOf(String actor) {
        return actor == null || actor.isBlank() ? Actor.system() : Actor.operator(actor.trim());
    }

    private static BatchId batchId(String raw) {
        try {
            return BatchId.fromString(raw);
        } catch (RuntimeException e) {
            throw InboundContractException.invalid("batchId", "is not a UUID", e);
        }
    }

    private static StreamId streamId(String raw) {
        try {
            return StreamId.of(raw);
        } catch (RuntimeException e) {
            throw InboundContractException.invalid("streamId", e.getMessage(), e);
        }
    }
}
