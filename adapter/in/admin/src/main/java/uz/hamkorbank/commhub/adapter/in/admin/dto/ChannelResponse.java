package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * A channel profile as the administration screen shows it (§11.2 "Каналы и провайдеры", FR-2.2).
 *
 * @param available whether the router may currently use the channel at all — one flag that answers
 *     "why is nothing going out over SMS?" before anybody opens a provider
 */
public record ChannelResponse(
        String channel,
        String status,
        String balancingStrategy,
        List<String> fallbackOrder,
        QuietHoursDto quietHours,
        QuotaDto quota,
        boolean available) {}
