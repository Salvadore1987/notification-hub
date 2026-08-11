package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One immutable audit record: who did what, when, to which entity, from where and why (FR-7.3, SEC-08).
 *
 * <p>The justification is a field of its own rather than a line smuggled into {@code after}. Every
 * destructive action of the panel asks the operator for one and promises that it reaches the journal
 * (ADR-0019, ADR-0038); a column labelled "after" holding "разобрано вручную" keeps that promise only to
 * the person who wrote the code.
 *
 * <p>Before/after are grouped into {@link Change} because a record here stays at eight components, and
 * because the pair is meaningless apart: an entry either carries a snapshot of a change or it does not.
 *
 * @param action verb of the operation, e.g. {@code batch.pause}, {@code dlq.retry}
 * @param change what the value was and what it became; empty for creations, deletions and read access
 * @param sourceIp IP the request came from; {@code null} for system actions
 * @param reason justification the operator gave, as received in {@code X-Commhub-Reason}; {@code null}
 *     when the action needs none or the operator confirmed without one
 */
public record AuditEntry(
        Actor actor,
        String action,
        String entityType,
        String entityId,
        Change change,
        String sourceIp,
        String reason,
        Instant occurredAt) {

    public AuditEntry {
        Guard.notNull(actor, "AuditEntry.actor");
        Guard.notBlank(action, "AuditEntry.action");
        Guard.notBlank(entityType, "AuditEntry.entityType");
        Guard.notNull(occurredAt, "AuditEntry.occurredAt");
        change = change == null ? Change.none() : change;
        reason = blankToNull(reason);
    }

    /**
     * State around one change, rendered for a reader.
     *
     * @param before state before it; {@code null} for creations and read access
     * @param after state after it; {@code null} for deletions and read access
     */
    public record Change(String before, String after) {

        private static final Change NONE = new Change(null, null);

        /** No snapshot: the action created nothing, changed nothing and deleted nothing. */
        public static Change none() {
            return NONE;
        }

        public static Change of(String before, String after) {
            return before == null && after == null ? NONE : new Change(before, after);
        }
    }

    /** Audit record of an action without a before/after snapshot. */
    public static AuditEntry of(Actor actor, String action, String entityType, String entityId, Instant occurredAt) {
        return new AuditEntry(actor, action, entityType, entityId, Change.none(), null, null, occurredAt);
    }

    /** Audit record carrying what the value was before the action and what it became. */
    public static AuditEntry changed(
            Actor actor, String action, String entityType, String entityId, Change change, Instant occurredAt) {
        return new AuditEntry(actor, action, entityType, entityId, change, null, null, occurredAt);
    }

    /** The same entry with the operator's justification attached (FR-7.3). */
    public AuditEntry withReason(String justification) {
        return new AuditEntry(actor, action, entityType, entityId, change, sourceIp, justification, occurredAt);
    }

    /** The same entry with the address the request came from attached (FR-7.3). */
    public AuditEntry withSourceIp(String address) {
        return new AuditEntry(actor, action, entityType, entityId, change, address, reason, occurredAt);
    }

    /** State before the change; {@code null} when the entry carries no snapshot. */
    public String before() {
        return change.before();
    }

    /** State after the change; {@code null} when the entry carries no snapshot. */
    public String after() {
        return change.after();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
