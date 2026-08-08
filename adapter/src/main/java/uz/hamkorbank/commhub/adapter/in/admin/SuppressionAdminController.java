package uz.hamkorbank.commhub.adapter.in.admin;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ImportResultResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.PageResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SuppressionCheckResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SuppressionRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SuppressionResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapper;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPaging;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.admin.support.SuppressionCsvCodec;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.port.in.GetSuppressions;
import uz.hamkorbank.commhub.application.port.in.ManageSuppressions;
import uz.hamkorbank.commhub.application.port.in.command.ReleaseSuppressionCommand;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionCheckQuery;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionQuery;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The suppression list (§11.2 "Suppression list", FR-5.1, ADMIN and OPERATOR).
 *
 * <p>The list has no filter by address and never will: the table stores hashes (DB-04), so "show me this
 * number" is a different question with a different answer, and that is what {@code /check} is for. It
 * takes the address in the clear, validates it through the value object of its channel and hashes it —
 * so a mistyped number is refused here rather than becoming a hash that quietly matches nothing.
 *
 * <p>Nothing here writes the automatic entries. A provider naming an address unusable, an email hard
 * bounce, an invalidated push token — those go through {@code SuppressionRegistrar} on the sending path
 * and are recorded with the provider as their author. What this screen adds is what a person decided.
 */
@RestController
@RequestMapping(AdminApi.SUPPRESSIONS)
public class SuppressionAdminController {

    private final GetSuppressions getSuppressions;
    private final ManageSuppressions manageSuppressions;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper viewMapper;
    private final AdminCommandMapper commandMapper;
    private final SuppressionCsvCodec csvCodec;

    public SuppressionAdminController(
            GetSuppressions getSuppressions,
            ManageSuppressions manageSuppressions,
            AuthenticatedCaller caller,
            AdminViewMapper viewMapper,
            AdminCommandMapper commandMapper,
            SuppressionCsvCodec csvCodec) {
        this.getSuppressions = Guard.notNull(getSuppressions, "getSuppressions");
        this.manageSuppressions = Guard.notNull(manageSuppressions, "manageSuppressions");
        this.caller = Guard.notNull(caller, "caller");
        this.viewMapper = Guard.notNull(viewMapper, "viewMapper");
        this.commandMapper = Guard.notNull(commandMapper, "commandMapper");
        this.csvCodec = Guard.notNull(csvCodec, "csvCodec");
    }

    /** {@code GET /api/admin/v1/suppressions} — one page of the list (FR-5.1, UI-03). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SUPPRESSION)
    public PageResponse<SuppressionResponse> list(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        SuppressionQuery query = new SuppressionQuery(
                AdminValues.optionalEnum(Channel.class, channel, "channel"),
                AdminValues.optionalEnum(SuppressionReason.class, reason, "reason"),
                AdminValues.parse(clientId, "clientId", ClientId::of),
                AdminPaging.limit(limit, SuppressionQuery.DEFAULT_LIMIT, SuppressionQuery.MAX_LIMIT),
                AdminPaging.offset(offset));
        List<SuppressionResponse> items = getSuppressions.list(query).stream()
                .map(viewMapper::toSuppression)
                .toList();
        // The list has no cheap count over a table that grows with every opt-out and every hard bounce;
        // the screen pages until a short page comes back, which is how this one is actually used.
        return PageResponse.of(items, items.size() + (long) query.offset(), query.limit(), query.offset());
    }

    /** {@code GET /api/admin/v1/suppressions/check} — "may the Hub still write to this address" (FR-5.1). */
    @GetMapping(path = "/check", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SUPPRESSION)
    public SuppressionCheckResponse check(
            @RequestParam String channel,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String clientId) {
        return viewMapper.toSuppressionCheck(getSuppressions.check(new SuppressionCheckQuery(
                AdminValues.requiredEnum(Channel.class, channel, "channel"),
                address,
                AdminValues.parse(clientId, "clientId", ClientId::of))));
    }

    /**
     * {@code POST /api/admin/v1/suppressions} — bans an address or a customer (FR-5.1).
     *
     * <p>Which of the two is decided by the body and exactly one of them may be given: banning an
     * address and banning a customer are different entries with different scopes, and a request naming
     * both would mean whichever the server happened to look at first.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SUPPRESSION)
    public SuppressionResponse add(@RequestBody SuppressionRequest request) {
        return viewMapper.toSuppression(suppress(request));
    }

    private SuppressionView suppress(SuppressionRequest request) {
        boolean hasAddress = request.address() != null && !request.address().isBlank();
        boolean hasClient = request.clientId() != null && !request.clientId().isBlank();
        if (hasAddress == hasClient) {
            throw InboundContractException.invalid(
                    "address|clientId", "exactly one of an address and a clientId has to be given");
        }
        return hasAddress
                ? manageSuppressions.suppressAddress(commandMapper.toSuppressAddress(request, caller.actor()))
                : manageSuppressions.suppressClient(commandMapper.toSuppressClient(request, caller.actor()));
    }

    /**
     * {@code POST /api/admin/v1/suppressions/import} — a CSV upload of the list (§11.2, FR-5.1).
     *
     * <p>Row by row through the very same use case a single addition goes through, so an imported entry
     * is indistinguishable from a typed one — same validation, same hashing, same audit entry. A row the
     * domain refuses becomes a reported failure and the rest still go in.
     *
     * <p>A row whose entry already exists is a conflict and counts as skipped rather than as an error:
     * uploading the same file twice has to be safe, because during a migration it happens.
     */
    @PostMapping(path = "/import", consumes = AdminApi.TEXT_CSV, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SUPPRESSION)
    public ImportResultResponse importCsv(@RequestBody String body) {
        List<SuppressionRequest> rows;
        try {
            rows = csvCodec.parse(new StringReader(body), ',');
        } catch (IOException e) {
            throw InboundContractException.invalid("file", "could not be read", e);
        }
        int imported = 0;
        int skipped = 0;
        List<ImportResultResponse.FailureDto> failures = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            int line = index + 2;
            try {
                suppress(rows.get(index));
                imported++;
            } catch (ConfigurationConflictException e) {
                skipped++;
            } catch (RuntimeException e) {
                failures.add(new ImportResultResponse.FailureDto(line, e.getMessage()));
            }
        }
        return new ImportResultResponse(imported, skipped, failures);
    }

    /** {@code DELETE /api/admin/v1/suppressions/{entryId}} — an opt-in after an opt-out (FR-5.1). */
    @DeleteMapping(path = "/{entryId}")
    @PreAuthorize(AdminAuthority.SUPPRESSION)
    public ResponseEntity<Void> release(
            @PathVariable String entryId,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        manageSuppressions.release(new ReleaseSuppressionCommand(
                caller.actor(), SuppressionEntryId.of(AdminValues.requiredUuid(entryId, "entryId")), reason));
        return ResponseEntity.noContent().build();
    }
}
