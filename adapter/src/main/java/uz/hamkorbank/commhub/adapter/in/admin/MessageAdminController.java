package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.MessageDigestResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.PageResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminMasking;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPaging;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPeriod;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageStatusResponse;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapper;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.MessageDigestView;
import uz.hamkorbank.commhub.application.port.in.GetMessage;
import uz.hamkorbank.commhub.application.port.in.SearchMessages;
import uz.hamkorbank.commhub.application.port.in.query.MessageQuery;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Message search and the message card (§11.2 "Сообщения", UI-03, SEC-08).
 *
 * <p>The card is the very same {@code GET /messages/{id}} body a source system gets, deliberately: the
 * screen an operator reads during an incident and the answer the Bank's system polled are then the same
 * document, and "the panel says something else" stops being a thing anybody can say. It also means the
 * timeline of §11.2 needs no second read model.
 *
 * <p>Both endpoints journal the read as access to personal data when the caller is a person (SEC-08).
 * That happens in the use case, inside the transaction that read the rows, so the list cannot be shown
 * without the entry being written.
 */
@RestController
@RequestMapping(AdminApi.MESSAGES)
public class MessageAdminController {

    private final SearchMessages searchMessages;
    private final GetMessage getMessage;
    private final AdminPeriod period;
    private final AdminMasking masking;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper mapper;
    private final RestResponseMapper cardMapper;

    public MessageAdminController(
            SearchMessages searchMessages,
            GetMessage getMessage,
            AdminPeriod period,
            AdminMasking masking,
            AuthenticatedCaller caller,
            AdminViewMapper mapper,
            RestResponseMapper cardMapper) {
        this.searchMessages = Guard.notNull(searchMessages, "searchMessages");
        this.getMessage = Guard.notNull(getMessage, "getMessage");
        this.period = Guard.notNull(period, "period");
        this.masking = Guard.notNull(masking, "masking");
        this.caller = Guard.notNull(caller, "caller");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.cardMapper = Guard.notNull(cardMapper, "cardMapper");
    }

    /**
     * {@code GET /api/admin/v1/messages} — one page of the message list (§11.2, UI-03).
     *
     * <p>{@code recipient} takes the address as the operator has it. The column is stored in the clear
     * precisely so this question can be asked (DB-05); how much of the answer comes back depends on the
     * role, and that decision is applied here rather than in the query.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR_OR_VIEWER)
    public PageResponse<MessageDigestResponse> search(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String streamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String externalMessageId,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String correlationId,
            SearchPage page) {
        MessageSearchQuery query = query(from, to, streamId, status, externalMessageId, recipient, correlationId, page);
        List<MessageDigestResponse> items =
                searchMessages.search(query).stream().map(this::toResponse).toList();
        return PageResponse.of(items, searchMessages.count(query), query.limit(), query.offset());
    }

    /** {@code GET /api/admin/v1/messages/{messageId}} — the card with its full timeline (§11.2). */
    @GetMapping(path = "/{messageId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.OPERATOR_OR_VIEWER)
    public MessageStatusResponse card(@PathVariable String messageId) {
        return cardMapper.toStatus(getMessage.get(
                MessageQuery.byId(MessageId.of(AdminValues.requiredUuid(messageId, "messageId")), caller.actor())));
    }

    private MessageDigestResponse toResponse(MessageDigestView view) {
        return mapper.toMessageDigest(view, masking.recipient(view.recipient()));
    }

    private MessageSearchQuery query(
            String from,
            String to,
            String streamId,
            String status,
            String externalMessageId,
            String recipient,
            String correlationId,
            SearchPage page) {
        AdminPeriod.Period resolved = period.resolve(from, to);
        return new MessageSearchQuery(
                resolved.from(),
                resolved.to(),
                new MessageSearchQuery.MessageFilter(externalMessageId, recipient, correlationId, page.batchId()),
                AdminValues.optionalEnum(MessageStatus.class, status, "status"),
                AdminValues.parse(streamId, "streamId", StreamId::of),
                caller.actor(),
                AdminPaging.limit(page.limit(), MessageSearchQuery.DEFAULT_LIMIT, MessageSearchQuery.MAX_LIMIT),
                AdminPaging.offset(page.offset()));
    }

    /**
     * The three parameters that would take the handler past the eight this project allows.
     *
     * <p>{@code batchId} travels with them rather than with the filters because on this screen it is
     * what a drill-down from the batch card sets, not something anybody types.
     */
    public record SearchPage(String batchId, Integer limit, Integer offset) {}
}
