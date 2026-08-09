package uz.hamkorbank.commhub.adapter.in.rest.dto;

/**
 * One refused item of a chunk (FR-1.4).
 *
 * @param reason machine-readable reason of IR-01, e.g. {@code VALIDATION_FAILED} or {@code SUPPRESSED}
 */
public record ItemRejectionResponse(String externalMessageId, String reason, String detail) {}
