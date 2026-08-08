package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Configuration of one channel for the administration screens (FR-2.2, FR-2.3, FR-2.7).
 *
 * @param fallbackOrder primary provider first, reserves in their configured order (FR-2.2)
 * @param available whether messages can currently be routed over the channel at all
 */
public record ChannelView(
        Channel channel,
        ChannelStatus status,
        BalancingStrategy balancingStrategy,
        List<ProviderCode> fallbackOrder,
        QuietHours quietHours,
        QuotaConfig quota,
        boolean available) {

    public ChannelView {
        Guard.notNull(channel, "ChannelView.channel");
        Guard.notNull(status, "ChannelView.status");
        Guard.notNull(balancingStrategy, "ChannelView.balancingStrategy");
        Guard.notNull(quota, "ChannelView.quota");
        fallbackOrder = Guard.copyOf(fallbackOrder);
    }

    public Optional<QuietHours> quietHoursOptional() {
        return Optional.ofNullable(quietHours);
    }
}
