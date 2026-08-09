package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.BatchActions;
import uz.hamkorbank.commhub.adapter.in.admin.dto.PageResponse;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPaging;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPeriod;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchActionResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchStatusResponse;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapper;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.port.in.GetBatch;
import uz.hamkorbank.commhub.application.port.in.GetBatches;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;
import uz.hamkorbank.commhub.application.port.in.query.BatchListQuery;
import uz.hamkorbank.commhub.application.port.in.query.BatchQuery;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The batch screens (§11.2 "Рассылки", FR-3.1, FR-3.2).
 *
 * <p>The card and the actions answer the very same bodies as the source-system endpoints of §8.2. What
 * differs is only who may call them and how they are authorised: a source system acts on its own
 * batches through a token that names its stream (SEC-01), an operator acts on anybody's through a role
 * (SEC-03) — and stopping somebody else's campaign is exactly the act that has to be journalled with a
 * person's name on it.
 *
 * <p>Drill-down to the messages of a batch is the message list with a {@code batchId} filter, not a
 * nested endpoint: it is the same list, with the same paging, the same masking and the same SEC-08
 * entry, and a second copy of it would eventually differ in one of the three.
 */
@RestController
@RequestMapping(AdminApi.BATCHES)
public class BatchAdminController {

    private static final String ACTION_START = "start";
    private static final String ACTION_PAUSE = "pause";
    private static final String ACTION_RESUME = "resume";
    private static final String ACTION_STOP = "stop";

    private final GetBatches getBatches;
    private final GetBatch getBatch;
    private final BatchActions actions;
    private final AdminPeriod period;
    private final AuthenticatedCaller caller;
    private final RestResponseMapper mapper;

    public BatchAdminController(
            GetBatches getBatches,
            GetBatch getBatch,
            BatchActions actions,
            AdminPeriod period,
            AuthenticatedCaller caller,
            RestResponseMapper mapper) {
        this.getBatches = Guard.notNull(getBatches, "getBatches");
        this.getBatch = Guard.notNull(getBatch, "getBatch");
        this.actions = Guard.notNull(actions, "actions");
        this.period = Guard.notNull(period, "period");
        this.caller = Guard.notNull(caller, "caller");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** {@code GET /api/admin/v1/batches} — one page of the batch list (§11.2, UI-03). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR_OR_VIEWER)
    public PageResponse<BatchStatusResponse> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String streamId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        BatchListQuery query = query(from, to, streamId, channel, status, activeOnly, limit, offset);
        List<BatchStatusResponse> items =
                getBatches.list(query).stream().map(mapper::toStatus).toList();
        return PageResponse.of(items, getBatches.count(query), query.limit(), query.offset());
    }

    /** {@code GET /api/admin/v1/batches/{batchId}} — the batch card with its progress (FR-3.1). */
    @GetMapping(path = "/{batchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR_OR_VIEWER)
    public BatchStatusResponse card(@PathVariable String batchId) {
        return mapper.toStatus(getBatch.get(BatchQuery.byId(batchId(batchId))));
    }

    /** {@code POST /api/admin/v1/batches/{batchId}/actions/{action}} — start, pause, resume, stop (FR-3.2). */
    @PostMapping(path = "/{batchId}/actions/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR)
    public BatchActionResponse act(
            @PathVariable String batchId,
            @PathVariable String action,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        BatchActionCommand command = new BatchActionCommand(batchId(batchId), caller.actor(), reason);
        return mapper.toAction(apply(action, command));
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

    private BatchListQuery query(
            String from,
            String to,
            String streamId,
            String channel,
            String status,
            boolean activeOnly,
            Integer limit,
            Integer offset) {
        AdminPeriod.Period resolved = period.resolve(from, to);
        return new BatchListQuery(
                resolved.from(),
                resolved.to(),
                AdminValues.parse(streamId, "streamId", StreamId::of),
                AdminValues.optionalEnum(Channel.class, channel, "channel"),
                AdminValues.optionalEnum(BatchStatus.class, status, "status"),
                activeOnly,
                AdminPaging.limit(limit, BatchListQuery.DEFAULT_LIMIT, BatchListQuery.MAX_LIMIT),
                AdminPaging.offset(offset));
    }

    private static BatchId batchId(String raw) {
        return BatchId.of(AdminValues.requiredUuid(raw, "batchId"));
    }
}
