package uz.hamkorbank.commhub.adapter.in.rest.dto;

/**
 * Answer to {@code POST /messages} (§8.2, 202 Accepted).
 *
 * <p>The status is the canonical one the message actually reached inside the accepting transaction —
 * {@code ROUTED} or {@code QUEUED} for a message on its way out (§6.3). A refusal never comes back
 * here: it is a {@code problem+json} with the machine-readable reason of IR-01.
 */
public record MessageAcceptedResponse(String messageId, String status) {}
