package uz.hamkorbank.commhub.application.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.AuditEntryView;
import uz.hamkorbank.commhub.application.mapper.AuditMapper;
import uz.hamkorbank.commhub.application.port.in.GetAuditLog;
import uz.hamkorbank.commhub.application.port.in.query.AuditQuery;
import uz.hamkorbank.commhub.application.port.out.AuditQueryPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Read side of the audit journal (FR-7.3, SEC-08).
 *
 * <p>Nothing but a page and a count. The export of FR-7.3 is the same query walked to the end — it is not
 * a second code path, because an export that reads differently from the screen is an export nobody can
 * reconcile with what they saw.
 *
 * <p>Reading the journal is itself not audited. It would make the log grow by looking at it, and the
 * question SEC-08 asks — who looked at a <em>customer's</em> data — is answered where that data is read,
 * not here.
 */
@Service
public class AuditQueryService implements GetAuditLog {

    private final AuditQueryPort journal;
    private final AuditMapper mapper;

    public AuditQueryService(AuditQueryPort journal, AuditMapper mapper) {
        this.journal = Guard.notNull(journal, "journal");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntryView> list(AuditQuery query) {
        Guard.notNull(query, "query");
        return journal.search(query).stream().map(mapper::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(AuditQuery query) {
        Guard.notNull(query, "query");
        return journal.count(query);
    }
}
