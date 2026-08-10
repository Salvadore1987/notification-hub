/**
 * Exception handling that belongs to the admin BFF — one advice per concern (project rule, IR-01).
 *
 * <p>Everything the panel shares with {@code /api/v1} — a not-found, a conflicting state, an unexpected
 * failure — is already answered by the advices of {@code adapter.in.rest.handlers}, which are global.
 * What lives here is what only this API can produce: authorisation, which exists on
 * {@code /api/admin/v1} alone because {@code @PreAuthorize} does.
 */
package uz.hamkorbank.commhub.adapter.in.admin.handlers;
