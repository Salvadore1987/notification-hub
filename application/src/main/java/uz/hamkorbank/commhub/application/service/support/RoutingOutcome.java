package uz.hamkorbank.commhub.application.service.support;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;
import uz.hamkorbank.commhub.domain.service.RoutingResult;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A routing decision together with the snapshot it was taken against (MP-05, AD-07).
 *
 * <p>The configuration travels with the result because the stages after routing need it too: the
 * quiet-hours window of the channel (FR-5.3) and the tariff of the chosen provider (FR-6.2).
 *
 * @param segments SMS segments of the message; 0 when it carries no SMS content (MP-06)
 */
public record RoutingOutcome(RoutingResult result, RoutingConfiguration configuration, int segments) {

    public RoutingOutcome {
        Guard.notNull(result, "RoutingOutcome.result");
        Guard.notNull(configuration, "RoutingOutcome.configuration");
        Guard.notNegative(segments, "RoutingOutcome.segments");
    }

    public boolean isRouted() {
        return result.isRouted();
    }

    public Optional<RoutingResult.Routed> routed() {
        return result.routed();
    }

    /** The decision, or a failure when there is none — call only after {@link #isRouted()}. */
    public RoutingResult.Routed requireRouted() {
        return result.routed().orElseThrow(() -> new IllegalStateException("no route was found"));
    }

    /** Why no route is available (FR-2.2, IR-01). */
    public Optional<RoutingResult.NoRoute> noRoute() {
        return result instanceof RoutingResult.NoRoute rejection ? Optional.of(rejection) : Optional.empty();
    }

    public Optional<ChannelConfig> channelConfig(Channel channel) {
        return configuration.channelConfig(channel);
    }
}
