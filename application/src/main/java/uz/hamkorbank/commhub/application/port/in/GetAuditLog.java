package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.AuditEntryView;
import uz.hamkorbank.commhub.application.port.in.query.AuditQuery;

/**
 * Reads the audit journal (FR-7.3, SEC-08, §11.2 "Аудит").
 *
 * <p>Read-only in the strict sense: there is no operation here that writes, and none anywhere else that
 * changes or deletes an entry — the table refuses {@code UPDATE}, {@code DELETE} and {@code TRUNCATE} in
 * the database itself (V7). An audit journal an administrator can tidy up is not one.
 *
 * <p>Who may call this is a role of its own ({@code SECURITY_AUDITOR}), because the journal contains what
 * everyone else did, including the administrators.
 */
public interface GetAuditLog {

    /** One page of entries, most recent first. */
    List<AuditEntryView> list(AuditQuery query);

    /** Total number of matching entries; the export uses it to know how far it has to walk. */
    long count(AuditQuery query);
}
