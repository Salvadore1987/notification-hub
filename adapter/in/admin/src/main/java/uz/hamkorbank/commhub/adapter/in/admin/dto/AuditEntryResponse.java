package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * One line of the audit journal (§11.2 "Аудит", FR-7.3, SEC-08).
 *
 * @param change what the value was and what it became; both {@code null} for creations and read access
 * @param reason justification the operator gave; {@code null} when the action needed none
 */
public record AuditEntryResponse(
        String occurredAt,
        String username,
        String action,
        String entityType,
        String entityId,
        Change change,
        String reason,
        String sourceIp) {

    /** State around one change, rendered. */
    public record Change(String before, String after) {}
}
