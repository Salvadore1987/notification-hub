package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One immutable audit record: who did what, when, to which entity and from where (FR-7.3, SEC-08).
 *
 * @param action verb of the operation, e.g. {@code batch.pause}, {@code dlq.retry}
 * @param before state before the change; {@code null} for creations and read access
 * @param after state after the change; {@code null} for deletions and read access
 * @param sourceIp IP the request came from; {@code null} for system actions
 */
public record AuditEntry(
        Actor actor,
        String action,
        String entityType,
        String entityId,
        String before,
        String after,
        String sourceIp,
        Instant occurredAt) {

    public AuditEntry {
        Guard.notNull(actor, "AuditEntry.actor");
        Guard.notBlank(action, "AuditEntry.action");
        Guard.notBlank(entityType, "AuditEntry.entityType");
        Guard.notNull(occurredAt, "AuditEntry.occurredAt");
    }

    /** Audit record of an operator action without a before/after snapshot. */
    public static AuditEntry of(Actor actor, String action, String entityType, String entityId, Instant occurredAt) {
        return new AuditEntry(actor, action, entityType, entityId, null, null, null, occurredAt);
    }
}
