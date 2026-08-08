package uz.hamkorbank.commhub.adapter.in.rest.dto;

import java.util.List;

/**
 * Answer to {@code POST /batches/{id}/items} (§8.2, FR-1.6).
 *
 * <p>A chunk is never rejected as a whole because some of its items are bad: the good ones are on
 * their way and every refused item is listed with its own reason, so the source system can resend
 * precisely those (FR-1.4, IR-01).
 */
public record BatchItemsResponse(
        String batchId,
        long accepted,
        long duplicates,
        long rejected,
        List<ItemRejectionResponse> rejections,
        BatchProgressResponse progress) {}
