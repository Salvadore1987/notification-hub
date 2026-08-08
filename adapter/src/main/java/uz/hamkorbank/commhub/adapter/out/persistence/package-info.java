/**
 * Driven adapter over PostgreSQL: implementations of the persistence output ports (Phase 4, §10).
 *
 * <p><strong>JdbcClient, not mapped entities.</strong> The domain aggregates are framework-free
 * (AR-02), so they carry no persistence annotations and cannot be entities. Reconstituting them takes
 * a factory or a rehydration builder, which neither JPA nor Spring Data JDBC can drive. The schema
 * also holds shapes a mapper would fight: composite primary keys on the partitioned tables (§10.1,
 * DB-02), {@code jsonb} columns and {@code ON CONFLICT} upserts. Explicit SQL through
 * {@link org.springframework.jdbc.core.simple.JdbcClient} keeps all of that visible in one place.
 *
 * <p><strong>Row mappers are hand-written.</strong> The MapStruct rule of this project covers the
 * application layer, where mapping is field-to-field between records. Here a row becomes an aggregate
 * through its factory and mutators, and {@code jsonb} columns pass through Jackson — a generated
 * mapper could express neither.
 *
 * <p>Packages: {@code config} — streams, channels, providers, routing policies; {@code template} —
 * templates and their versions; {@code messaging} — batches, messages, status history, attempts;
 * {@code delivery} — outbox, DLQ, dedup registry, suppression list, quotas; {@code audit} — the audit
 * log; {@code support} — JSON codec and shared SQL helpers.
 */
package uz.hamkorbank.commhub.adapter.out.persistence;
