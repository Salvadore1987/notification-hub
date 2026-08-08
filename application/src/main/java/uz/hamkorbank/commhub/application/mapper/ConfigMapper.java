package uz.hamkorbank.commhub.application.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.ChannelView;
import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.dto.RouteEvaluationView;
import uz.hamkorbank.commhub.application.dto.RoutingPolicyView;
import uz.hamkorbank.commhub.application.dto.StreamView;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.service.RoutingResult;

/**
 * Conversions of the configuration aggregates into the administration read models (FR-2.1…FR-2.7).
 *
 * <p>Default methods for the same reason as {@code MessageMapper}: the aggregates expose fluent
 * accessors and {@code Optional} state that the generator cannot introspect. The rule the mapper
 * upholds is the one that matters — no use case builds a view itself.
 */
@Mapper(componentModel = "spring")
public interface ConfigMapper {

    default ProviderView toView(Provider provider, Map<String, String> endpointConfig) {
        return new ProviderView(
                provider.id(),
                provider.code(),
                provider.channel(),
                provider.adapterType(),
                provider.weight(),
                provider.tariff().orElse(null),
                provider.rateLimit(),
                new ProviderView.State(
                        provider.isEnabled(),
                        provider.isInMaintenance(),
                        provider.health(),
                        provider.isSelectable(),
                        provider.quota(),
                        provider.credentialsRef().orElse(null),
                        endpointConfig));
    }

    default ChannelView toView(ChannelConfig config) {
        return new ChannelView(
                config.channel(),
                config.status(),
                config.balancingStrategy(),
                config.fallbackOrder().stream().map(ProviderRef::code).toList(),
                config.quietHours().orElse(null),
                config.quota(),
                config.isAvailable());
    }

    default StreamView toView(Stream stream, Instant now) {
        return new StreamView(
                stream.id(),
                stream.name(),
                stream.integrationType(),
                stream.status(),
                stream.connectionStatus(now),
                stream.defaults(),
                new StreamView.Limits(
                        stream.quota(), stream.rateLimit(), stream.quietHours().orElse(null)),
                stream.lastActivityAt().orElse(null));
    }

    default RoutingPolicyView toView(RoutingPolicy policy) {
        return new RoutingPolicyView(
                policy.id(), policy.match(), policy.action(), policy.priority(), policy.isEnabled());
    }

    /** Dry-run answer of a successful routing decision (FR-8.9). */
    default RouteEvaluationView toView(RoutingResult.Routed routed, int segments, Money estimatedCost) {
        List<ProviderCode> fallbacks =
                routed.fallbackProviders().stream().map(ProviderRef::code).toList();
        return new RouteEvaluationView(
                true,
                routed.channel(),
                routed.provider().code(),
                fallbacks,
                routed.strategy(),
                segments,
                estimatedCost,
                null);
    }

    /** Dry-run answer when the configuration offers no route (FR-8.9, IR-01). */
    default RouteEvaluationView toView(RoutingResult.NoRoute noRoute, int segments) {
        return new RouteEvaluationView(
                false,
                null,
                null,
                List.of(),
                null,
                segments,
                null,
                new RouteEvaluationView.Rejection(noRoute.reason(), noRoute.detail()));
    }
}
