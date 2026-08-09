/**
 * Request and response bodies of the admin BFF (§11.2).
 *
 * <p>Every value object is flattened to the string form the contract publishes, the same rule the
 * source-system DTOs follow: the SPA integrates against {@code "messageId": "0192…"} and not against
 * whatever shape {@code MessageId} happens to have this release.
 *
 * <p>Requests and responses share the small editable structures — a quota, a rate limit, a quiet-hours
 * window — rather than each having its own copy. An operator edits what they were shown, and a form
 * whose fields are not exactly the fields of the answer is a form that silently drops the ones it
 * forgot about.
 */
package uz.hamkorbank.commhub.adapter.in.admin.dto;
