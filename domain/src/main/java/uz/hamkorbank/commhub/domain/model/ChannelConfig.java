package uz.hamkorbank.commhub.domain.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Configuration of one channel: status, balancing strategy and ordered provider fallback (§6.1,
 * FR-2.2, FR-2.3, FR-2.7).
 *
 * <p>Named {@code ChannelConfig} — the {@code Channel} aggregate of SRS §6.1 — to keep the name
 * {@link Channel} for the channel enum used all over the pipeline and in the outbound status format
 * (§6.4). Its identity <em>is</em> the channel.
 *
 * <p>Configuration changes are applied at runtime without a restart (AD-07, NF-07).
 */
public final class ChannelConfig extends AggregateRoot<Channel> {

    private ChannelStatus status;
    private BalancingStrategy balancingStrategy;
    private List<ProviderRef> fallbackOrder;
    private QuietHours quietHours;

    private ChannelConfig(Channel channel, ChannelStatus status, BalancingStrategy balancingStrategy) {
        super(channel);
        this.status = Guard.notNull(status, "ChannelConfig.status");
        this.balancingStrategy = Guard.notNull(balancingStrategy, "ChannelConfig.balancingStrategy");
        this.fallbackOrder = List.of();
    }

    /** Registers a channel with its balancing strategy; providers are attached afterwards. */
    public static ChannelConfig of(Channel channel, BalancingStrategy balancingStrategy) {
        return new ChannelConfig(channel, ChannelStatus.ACTIVE, balancingStrategy);
    }

    public Channel channel() {
        return id();
    }

    /**
     * Replaces the ordered provider list: the first entry is the primary provider, the rest are the
     * fallbacks in their configured order (FR-2.2).
     */
    public void updateFallbackOrder(List<ProviderRef> orderedProviders) {
        List<ProviderRef> providers = Guard.copyOf(orderedProviders);
        Guard.isTrue(new LinkedHashSet<>(providers).size() == providers.size(), "fallback order must be distinct");
        providers.forEach(provider -> Guard.isTrue(
                provider.channel() == channel(),
                "provider %s does not serve channel %s".formatted(provider.code(), channel())));
        this.fallbackOrder = providers;
    }

    public void updateBalancingStrategy(BalancingStrategy strategy) {
        this.balancingStrategy = Guard.notNull(strategy, "strategy");
    }

    /** Channel-level quiet hours; the stream-level window takes precedence when both exist (FR-5.3). */
    public void updateQuietHours(QuietHours newQuietHours) {
        this.quietHours = newQuietHours;
    }

    public void activate() {
        this.status = ChannelStatus.ACTIVE;
    }

    public void enterMaintenance() {
        this.status = ChannelStatus.MAINTENANCE;
    }

    public void disable() {
        this.status = ChannelStatus.DISABLED;
    }

    public boolean isAvailable() {
        return status.acceptsTraffic() && !fallbackOrder.isEmpty();
    }

    public Optional<ProviderRef> primaryProvider() {
        return fallbackOrder.isEmpty() ? Optional.empty() : Optional.of(fallbackOrder.getFirst());
    }

    /** Providers configured after the given one, i.e. its remaining fallbacks (FR-2.2). */
    public List<ProviderRef> providersAfter(ProviderRef provider) {
        int position = fallbackOrder.indexOf(provider);
        if (position < 0 || position == fallbackOrder.size() - 1) {
            return List.of();
        }
        return List.copyOf(fallbackOrder.subList(position + 1, fallbackOrder.size()));
    }

    public ChannelStatus status() {
        return status;
    }

    public BalancingStrategy balancingStrategy() {
        return balancingStrategy;
    }

    public List<ProviderRef> fallbackOrder() {
        return fallbackOrder;
    }

    public Optional<QuietHours> quietHours() {
        return Optional.ofNullable(quietHours);
    }
}
