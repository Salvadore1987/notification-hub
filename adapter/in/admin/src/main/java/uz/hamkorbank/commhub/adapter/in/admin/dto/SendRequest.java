package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.Map;

/**
 * One message an operator sends from the panel (§11.2 "Отправка", ADR-0038).
 *
 * <p>There is no text field: content comes only from a published template, so the panel cannot send a
 * wording nobody reviewed (FR-4.2).
 *
 * @param externalId identifier of the send; optional, generated when absent
 */
public record SendRequest(
        String streamId,
        String templateCode,
        String locale,
        String channel,
        String trafficClass,
        RecipientDto recipient,
        Map<String, String> variables,
        String externalId) {}
