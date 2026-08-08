package uz.hamkorbank.commhub.application.port.out;

/**
 * Append-only audit journal of user actions and access to personal data (§10.1 {@code audit_log},
 * FR-7.3, SEC-08).
 */
public interface AuditPort {

    void write(AuditEntry entry);
}
