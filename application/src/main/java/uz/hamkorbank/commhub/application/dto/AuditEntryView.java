package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One entry of the audit journal as the admin panel and the export show it (FR-7.3, SEC-08).
 *
 * <p>The user is a name and not an identifier: the journal has to stay readable after the account is
 * gone, which is also why {@code audit_log} keeps the name the action was performed under.
 *
 * @param change state before and after, rendered; empty for creations, deletions and read access
 * @param reason justification the operator gave for the action; {@code null} when there was none
 * @param sourceIp address the action came from, as FR-7.3 requires; {@code null} for the Hub's own
 */
public record AuditEntryView(
        Instant occurredAt,
        String username,
        String action,
        String entityType,
        String entityId,
        Change change,
        String reason,
        String sourceIp) {

    public AuditEntryView {
        Guard.notNull(occurredAt, "AuditEntryView.occurredAt");
        Guard.notBlank(action, "AuditEntryView.action");
        Guard.notBlank(entityType, "AuditEntryView.entityType");
        change = change == null ? Change.none() : change;
    }

    /** State around one change, rendered for a reader. */
    public record Change(String before, String after) {

        private static final Change NONE = new Change(null, null);

        public static Change none() {
            return NONE;
        }
    }

    /** State before the change; {@code null} when the entry carries no snapshot. */
    public String before() {
        return change.before();
    }

    /** State after the change; {@code null} when the entry carries no snapshot. */
    public String after() {
        return change.after();
    }
}
