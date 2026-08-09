package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.DlqActionRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.DlqActionResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.DlqEntryResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.PageResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPaging;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.port.in.ArchiveDlq;
import uz.hamkorbank.commhub.application.port.in.GetDlq;
import uz.hamkorbank.commhub.application.port.in.ResendDlq;
import uz.hamkorbank.commhub.application.port.in.command.ArchiveDlqCommand;
import uz.hamkorbank.commhub.application.port.in.command.ResendDlqCommand;
import uz.hamkorbank.commhub.application.port.in.query.DlqQuery;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The dead-letter queue (§11.2 "DLQ", FR-3.3).
 *
 * <p>Both actions take an explicit list of message ids and never a filter. "Retry everything matching
 * this" is one mistyped field away from resending a day of traffic to customers who already received
 * it; what the screen does is show a filtered page and let somebody select from it.
 *
 * <p>Both answer 200 with what was applied and what was skipped, rather than failing on the first
 * entry that could not be used. A selection is made against a list that keeps moving underneath, and an
 * entry somebody else retried a second earlier must not cost the operator the other forty-nine.
 */
@RestController
@RequestMapping(AdminApi.DLQ)
public class DlqAdminController {

    private final GetDlq getDlq;
    private final ResendDlq resendDlq;
    private final ArchiveDlq archiveDlq;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper mapper;

    public DlqAdminController(
            GetDlq getDlq,
            ResendDlq resendDlq,
            ArchiveDlq archiveDlq,
            AuthenticatedCaller caller,
            AdminViewMapper mapper) {
        this.getDlq = Guard.notNull(getDlq, "getDlq");
        this.resendDlq = Guard.notNull(resendDlq, "resendDlq");
        this.archiveDlq = Guard.notNull(archiveDlq, "archiveDlq");
        this.caller = Guard.notNull(caller, "caller");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /**
     * {@code GET /api/admin/v1/dlq} — one page of the queue, oldest first (§11.2, UI-03).
     *
     * <p>Unlike the other lists this one has no period by default. {@code dlq_entry} is not partitioned
     * and stays the size of what nobody has dealt with yet, and the queue is read to find the oldest
     * thing in it — a screen that silently showed only the last day would hide exactly that.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR)
    public PageResponse<DlqEntryResponse> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "false") boolean includeRetried,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        DlqQuery query = new DlqQuery(
                AdminValues.instant(from, "from"),
                AdminValues.instant(to, "to"),
                AdminValues.optionalEnum(RejectionReason.class, reason, "reason"),
                includeRetried,
                includeArchived,
                AdminPaging.limit(limit, DlqQuery.DEFAULT_LIMIT, DlqQuery.MAX_LIMIT),
                AdminPaging.offset(offset));
        List<DlqEntryResponse> items =
                getDlq.list(query).stream().map(mapper::toDlqEntry).toList();
        return PageResponse.of(items, getDlq.count(query), query.limit(), query.offset());
    }

    /** {@code POST /api/admin/v1/dlq/retry} — manual retry, single or in bulk (FR-3.3). */
    @PostMapping(
            path = "/retry",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR)
    public DlqActionResponse retry(@RequestBody DlqActionRequest request) {
        return mapper.toDlqAction(resendDlq.resend(new ResendDlqCommand(messageIds(request), caller.actor())));
    }

    /** {@code POST /api/admin/v1/dlq/archive} — takes entries off the working list (FR-3.3). */
    @PostMapping(
            path = "/archive",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR)
    public DlqActionResponse archive(
            @RequestBody DlqActionRequest request,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        return mapper.toDlqAction(
                archiveDlq.archive(new ArchiveDlqCommand(messageIds(request), caller.actor(), reason)));
    }

    private static List<MessageId> messageIds(DlqActionRequest request) {
        if (request == null
                || request.messageIds() == null
                || request.messageIds().isEmpty()) {
            throw InboundContractException.missing("messageIds");
        }
        return request.messageIds().stream()
                .map(id -> MessageId.of(AdminValues.requiredUuid(id, "messageIds")))
                .toList();
    }
}
