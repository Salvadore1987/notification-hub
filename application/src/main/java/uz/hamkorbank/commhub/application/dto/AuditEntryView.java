package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One entry of the audit journal as the admin panel and the export show it (FR-7.3, SEC-08).
 *
 * <p>The user is a name and not an identifier: the journal has to stay readable after the account is
 * gone, which is also why {@code audit_log} keeps the name the action was performed under.
 *
 * @param before state before the change, rendered; {@code null} for creations and for read access
 * @param after state after the change, rendered; {@code null} for deletions and for read access
 * @param sourceIp address the action came from, as FR-7.3 requires; {@code null} for the Hub's own
 */
public record AuditEntryView(
        Instant occurredAt,
        String username,
        String action,
        String entityType,
        String entityId,
        String before,
        String after,
        String sourceIp) {

    public AuditEntryView {
        Guard.notNull(occurredAt, "AuditEntryView.occurredAt");
        Guard.notBlank(action, "AuditEntryView.action");
        Guard.notBlank(entityType, "AuditEntryView.entityType");
    }
}
