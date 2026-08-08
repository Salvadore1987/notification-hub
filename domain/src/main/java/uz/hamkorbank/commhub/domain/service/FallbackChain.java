package uz.hamkorbank.commhub.domain.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reserve order for delivery attempts (FR-2.2, MP-03).
 *
 * <p>Two levels of fallback:
 *
 * <ul>
 *   <li>inside a channel — the ordered provider list of the channel configuration (FR-2.2);
 *   <li>across channels — the {@link ChannelPlan} chain, e.g. PUSH → SMS (MP-03, FR-8.1).
 * </ul>
 *
 * <p>Pure navigation over configuration: no state, no side effects.
 */
public final class FallbackChain {

    /**
     * Providers of a channel in fallback order, primary first, unusable ones removed (FR-2.2, FR-2.7).
     *
     * <p>A provider is kept when it is selectable, i.e. enabled, out of maintenance and not reported
     * {@code DOWN} by health checks (PR-02, FR-6.3).
     */
    public List<ProviderRef> providerChain(ChannelConfig channelConfig, Collection<Provider> providers) {
        Guard.notNull(channelConfig, "channelConfig");
        Guard.notNull(providers, "providers");
        List<ProviderRef> chain = new ArrayList<>();
        for (ProviderRef configured : channelConfig.fallbackOrder()) {
            providers.stream()
                    .filter(provider -> provider.id().equals(configured.id()))
                    .filter(Provider::isSelectable)
                    .findFirst()
                    .ifPresent(provider -> chain.add(provider.ref()));
        }
        return List.copyOf(chain);
    }

    /** Next provider of the chain after the one that failed (FR-2.2, PR-01). */
    public Optional<ProviderRef> nextProvider(List<ProviderRef> chain, ProviderRef failed) {
        Guard.notNull(chain, "chain");
        Guard.notNull(failed, "failed");
        int position = chain.indexOf(failed);
        if (position < 0 || position == chain.size() - 1) {
            return Optional.empty();
        }
        return Optional.of(chain.get(position + 1));
    }

    /** First provider of the chain that has not been tried yet (PR-01, FR-6.3). */
    public Optional<ProviderRef> nextUntried(List<ProviderRef> chain, Set<ProviderRef> alreadyTried) {
        Guard.notNull(chain, "chain");
        Guard.notNull(alreadyTried, "alreadyTried");
        return chain.stream()
                .filter(candidate -> !alreadyTried.contains(candidate))
                .findFirst();
    }

    /** Next channel of a cross-channel fallback chain, e.g. PUSH → SMS (MP-03, FR-8.1). */
    public Optional<Channel> nextChannel(ChannelPlan channelPlan, Channel current) {
        Guard.notNull(channelPlan, "channelPlan");
        Guard.notNull(current, "current");
        return channelPlan.nextAfter(current);
    }
}
