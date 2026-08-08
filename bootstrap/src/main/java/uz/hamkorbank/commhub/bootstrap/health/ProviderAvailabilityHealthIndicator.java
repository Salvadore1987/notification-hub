package uz.hamkorbank.commhub.bootstrap.health;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.Channel;

/**
 * Whether every channel still has a provider that can take traffic (FR-6.3, OBS-04, NF-05).
 *
 * <p>The question is per channel and not per provider. One provider going {@code DOWN} is the normal
 * working of the failover of PR-01 and is visible in the metrics; what nobody may miss is a channel where
 * the last selectable provider went away, because from that moment every message on it is rejected with
 * {@code NO_ROUTE_AVAILABLE} — and the source systems find out before operations does.
 *
 * <p>Like the broker check, this is <strong>not</strong> in the readiness group: restarting this instance
 * would not bring a provider back, and taking it out of the load balancer would only move the same
 * rejections to another pod. It is a health detail and the source of an alert.
 *
 * <p>Channels with no configured provider at all are ignored rather than reported down — a deployment
 * that has not enabled push yet is not a degraded one.
 */
@Component
public class ProviderAvailabilityHealthIndicator implements HealthIndicator {

    private final ProviderConfigRepository providers;

    public ProviderAvailabilityHealthIndicator(ProviderConfigRepository providers) {
        this.providers = providers;
    }

    @Override
    public Health health() {
        List<Provider> configured = providers.findAllProviders();
        Map<Channel, Long> selectable = new EnumMap<>(Channel.class);
        Map<Channel, Long> total = new EnumMap<>(Channel.class);
        for (Provider provider : configured) {
            total.merge(provider.channel(), 1L, Long::sum);
            selectable.merge(provider.channel(), provider.isSelectable() ? 1L : 0L, Long::sum);
        }
        boolean degraded = total.keySet().stream().anyMatch(channel -> selectable.getOrDefault(channel, 0L) == 0L);
        Health.Builder health = degraded ? Health.down() : Health.up();
        total.forEach((channel, count) ->
                health.withDetail(channel.name(), selectable.getOrDefault(channel, 0L) + "/" + count + " selectable"));
        return health.build();
    }
}
