package uz.hamkorbank.commhub.adapter.in.admin.dto;

/** One line of the audit journal (§11.2 "Аудит", FR-7.3, SEC-08). */
public record AuditEntryResponse(
        String occurredAt,
        String username,
        String action,
        String entityType,
        String entityId,
        String before,
        String after,
        String sourceIp) {}
