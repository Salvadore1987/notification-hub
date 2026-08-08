/**
 * Query records of the read-only use cases (§8.2 {@code GET /messages}, {@code GET /batches/{id}}).
 *
 * <p>Separate from {@code port.in.command} for the same reason the use cases are separate: a query
 * answers with a view and changes nothing, so it opens a read-only transaction, needs no actor and
 * emits no status event. Transport adapters translate their path and query parameters into these
 * records and never reach for a repository themselves (AR-06).
 */
package uz.hamkorbank.commhub.application.port.in.query;
