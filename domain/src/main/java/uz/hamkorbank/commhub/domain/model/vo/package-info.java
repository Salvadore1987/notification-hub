/**
 * Value objects and identifiers of the domain (SRS §5.2, AR-02).
 *
 * <p>All of them are immutable records that validate their invariants in the canonical constructor
 * and raise {@code DomainValidationException} on violation (FR-1.4). Identifiers of persisted
 * aggregates are UUIDv7 (project rule, DB-02). PII-carrying values expose a {@code masked()} form
 * for logs and UI (DB-04, OBS-03).
 */
package uz.hamkorbank.commhub.domain.model.vo;
