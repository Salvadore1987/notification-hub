package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.AuditEntryResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.PageResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPaging;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.admin.support.CsvRenderer;
import uz.hamkorbank.commhub.application.dto.AuditEntryView;
import uz.hamkorbank.commhub.application.port.in.GetAuditLog;
import uz.hamkorbank.commhub.application.port.in.query.AuditQuery;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The audit journal (§11.2 "Аудит", FR-7.3, SEC-08; SECURITY_AUDITOR and ADMIN).
 *
 * <p>The export walks the very same query the screen reads, page by page to the end. That is the whole
 * point of doing it this way: an export produced by a second query is one nobody can reconcile with what
 * they were looking at when they asked for it.
 *
 * <p>Reading the journal is not itself audited. It would make the log grow by looking at it, and the
 * question SEC-08 asks — who looked at a customer's data — is answered where that data is read, not
 * here.
 */
@RestController
@RequestMapping(AdminApi.AUDIT)
public class AuditController {

    private static final List<String> CSV_HEADER = List.of(
            "occurredAt", "username", "action", "entityType", "entityId", "before", "after", "reason", "sourceIp");

    /**
     * How far an export walks.
     *
     * <p>A bound rather than a policy: the journal only grows, nothing may be deleted from it, and an
     * unbounded export of a table kept for years is one request that holds a connection until somebody
     * restarts the pod. An auditor who hits it narrows the period, which is what the filters are for —
     * and the response says so rather than truncating silently.
     */
    private static final int EXPORT_MAX_ROWS = 50_000;

    private final GetAuditLog auditLog;
    private final AdminViewMapper mapper;

    public AuditController(GetAuditLog auditLog, AdminViewMapper mapper) {
        this.auditLog = Guard.notNull(auditLog, "auditLog");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** {@code GET /api/admin/v1/audit} — one page of the journal, most recent first (FR-7.3, UI-03). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.AUDITOR)
    public PageResponse<AuditEntryResponse> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        AuditQuery query = query(from, to, username, action, entityType, entityId, limit, offset);
        List<AuditEntryResponse> items =
                auditLog.list(query).stream().map(mapper::toAuditEntry).toList();
        return PageResponse.of(items, auditLog.count(query), query.limit(), query.offset());
    }

    /**
     * {@code GET /api/admin/v1/audit/export} — the same query, walked to the end, as CSV (FR-7.3).
     *
     * <p>{@code X-Commhub-Truncated} is set when the walk stopped at {@link #EXPORT_MAX_ROWS}. A cap
     * that is not announced is an export that looks complete and is not, which for an audit file is the
     * one failure mode that matters.
     */
    @GetMapping(path = "/export", produces = AdminApi.TEXT_CSV)
    @PreAuthorize(AdminAuthority.AUDITOR)
    public ResponseEntity<String> export(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId) {
        List<AuditEntryView> rows = new ArrayList<>();
        AuditQuery page = query(from, to, username, action, entityType, entityId, AuditQuery.MAX_LIMIT, 0);
        boolean truncated = false;
        while (true) {
            List<AuditEntryView> entries = auditLog.list(page);
            rows.addAll(entries);
            if (entries.size() < page.limit()) {
                break;
            }
            if (rows.size() >= EXPORT_MAX_ROWS) {
                truncated = true;
                break;
            }
            page = page.nextPage();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"commhub-audit.csv\"")
                .header("X-Commhub-Truncated", Boolean.toString(truncated))
                .contentType(MediaType.parseMediaType(AdminApi.TEXT_CSV + ";charset=UTF-8"))
                .body(CsvRenderer.render(CSV_HEADER, rows, AuditController::toCells));
    }

    private static AuditQuery query(
            String from,
            String to,
            String username,
            String action,
            String entityType,
            String entityId,
            Integer limit,
            Integer offset) {
        return new AuditQuery(
                AdminValues.instant(from, "from"),
                AdminValues.instant(to, "to"),
                username,
                action,
                entityType,
                entityId,
                AdminPaging.limit(limit, AuditQuery.DEFAULT_LIMIT, AuditQuery.MAX_LIMIT),
                AdminPaging.offset(offset));
    }

    private static List<String> toCells(AuditEntryView entry) {
        return Arrays.asList(
                entry.occurredAt().toString(),
                entry.username(),
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                entry.before(),
                entry.after(),
                entry.reason(),
                entry.sourceIp());
    }
}
