package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * One entry of the dead-letter queue (§11.2 "DLQ", FR-3.3).
 *
 * @param retryable whether the buttons on this row do anything; an entry may be retried once, and the
 *     client shows the state rather than deriving it from the other three fields
 */
public record DlqEntryResponse(
        String messageId,
        String reason,
        String lastError,
        String movedAt,
        String retriedBy,
        String retriedAt,
        boolean archived,
        boolean retryable) {}
