package uz.hamkorbank.commhub.adapter.in.rest.dto;

import java.util.List;

/**
 * Answer to {@code GET /messages/{id}} and {@code GET /messages?externalMessageId=&streamId=} (§8.2).
 *
 * <p>Carries no content and no recipient address: the source system already knows both, and repeating
 * them here would put message text and MSISDNs into every status poll (SEC-03).
 */
public record MessageStatusResponse(
        String messageId,
        String streamId,
        String externalMessageId,
        String batchId,
        String status,
        String reason,
        DeliveryResponse delivery,
        List<TransitionResponse> history) {}
