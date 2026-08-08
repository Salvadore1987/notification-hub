package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * One entry of the suppression list (§11.2 "Suppression list", FR-5.1).
 *
 * <p>The address is a hash and stays one. The table stores hashes (DB-04) and there is nothing to
 * unmask: "is this number on the list" is a different question with its own endpoint, which takes the
 * address, hashes it and answers about that one row.
 *
 * @param createdBy an operator's login, or the provider code that reported the address as unusable
 * @param validUntil {@code null} for an entry that does not expire
 */
public record SuppressionResponse(
        String entryId,
        String channel,
        String addressHash,
        String clientId,
        String reason,
        String validUntil,
        String createdBy,
        String createdAt) {}
