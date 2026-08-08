package uz.hamkorbank.commhub.application.port.in.query;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A page of the audit journal (FR-7.3, SEC-08, UI-03).
 *
 * <p>Every filter is optional — {@code null} means "any" — except the page size. The journal only grows
 * (nothing in it may be deleted, which is the point of an append-only log), so there is no size at which
 * reading all of it stays safe, and the export of FR-7.3 walks it page by page for the same reason.
 *
 * <p>Two filters answer the two questions the journal is read for: {@code username} answers "what did
 * this person do", and {@code entityType} with {@code entityId} answers "who touched this provider, this
 * template, this client's messages" — the second being the shape SEC-08 asks about.
 */
public record AuditQuery(
        Instant from,
        Instant to,
        String username,
        String action,
        String entityType,
        String entityId,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 50;

    /** Bounded, but generously: an export reads pages of this size, an interactive list reads fifty. */
    public static final int MAX_LIMIT = 1_000;

    public AuditQuery {
        Guard.isTrue(limit <= MAX_LIMIT, "AuditQuery.limit exceeds " + MAX_LIMIT);
        Guard.positive(limit, "AuditQuery.limit");
        Guard.notNegative(offset, "AuditQuery.offset");
        Guard.isTrue(from == null || to == null || !to.isBefore(from), "AuditQuery.to precedes AuditQuery.from");
    }

    /** Most recent entries, unfiltered. */
    public static AuditQuery firstPage() {
        return new AuditQuery(null, null, null, null, null, null, DEFAULT_LIMIT, 0);
    }

    /** Everything that happened to one entity, oldest page first (FR-7.3). */
    public static AuditQuery ofEntity(String entityType, String entityId) {
        Guard.notBlank(entityType, "entityType");
        return new AuditQuery(null, null, null, null, entityType, entityId, DEFAULT_LIMIT, 0);
    }

    /** The same query one page further on; used by the export walk. */
    public AuditQuery nextPage() {
        return new AuditQuery(from, to, username, action, entityType, entityId, limit, offset + limit);
    }
}
