package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * Body of configuring a channel (§11.2 "Каналы и провайдеры", FR-2.2, FR-2.3, FR-2.6).
 *
 * @param fallbackOrder provider codes in the order the channel falls back through; the order <em>is</em>
 *     the configuration, so the whole list is always sent rather than edited item by item
 */
public record ChannelRequest(
        String balancingStrategy, List<String> fallbackOrder, QuietHoursDto quietHours, QuotaDto quota) {}
