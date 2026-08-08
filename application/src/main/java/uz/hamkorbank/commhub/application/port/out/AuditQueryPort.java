package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import uz.hamkorbank.commhub.application.port.in.query.AuditQuery;

/**
 * Read side of the append-only audit journal (FR-7.3, SEC-08).
 *
 * <p>Separate from {@link AuditPort} on purpose. That port is used by every use case that changes
 * something and must stay as narrow as a write can be — a journal you can read from the same handle you
 * write with invites a use case to check its own history mid-transaction. This one is used by exactly
 * one query service and by the export.
 */
public interface AuditQueryPort {

    /** Entries matching the filters, most recent first. */
    List<AuditEntry> search(AuditQuery query);

    /** How many entries match, ignoring paging; what the admin list shows as the total (UI-03). */
    long count(AuditQuery query);
}
