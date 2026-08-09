package uz.hamkorbank.commhub.adapter.in.rest.dto;

/**
 * Answer to {@code GET /batches/{id}} and to the action endpoints (§8.2, FR-3.1, FR-3.2).
 *
 * @param total announced items; grows while chunks are still being uploaded
 * @param costEstimate expected cost by the tariffs of the routed provider, absent until it is known
 */
public record BatchStatusResponse(
        String batchId,
        String streamId,
        String channel,
        String status,
        long total,
        BatchProgressResponse progress,
        String createdAt,
        String costEstimate) {}
