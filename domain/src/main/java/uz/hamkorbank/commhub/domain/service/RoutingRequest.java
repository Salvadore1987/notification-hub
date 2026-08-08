package uz.hamkorbank.commhub.domain.service;

import java.util.Set;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Input of a routing decision (MP-05).
 *
 * @param message message to route; the router only reads its envelope, plan, content and recipient
 * @param segments SMS segment count from {@code SegmentCalculator}; used by least-cost routing (FR-2.3)
 * @param rotation monotonically growing counter kept by the application layer; makes round-robin and
 *     weighted balancing deterministic and keeps the domain service stateless (FR-2.3)
 * @param excludedProviders providers already tried for this message, skipped on failover (FR-6.3)
 */
public record RoutingRequest(Message message, int segments, long rotation, Set<ProviderRef> excludedProviders) {

    public RoutingRequest {
        Guard.notNull(message, "RoutingRequest.message");
        Guard.notNegative(segments, "RoutingRequest.segments");
        Guard.notNegative(rotation, "RoutingRequest.rotation");
        excludedProviders = Guard.copyOf(excludedProviders);
    }

    /** First routing of a message: nothing tried yet. */
    public static RoutingRequest of(Message message, int segments, long rotation) {
        return new RoutingRequest(message, segments, rotation, Set.of());
    }

    /** Re-routing after a provider failure (FR-6.3, PR-01). */
    public RoutingRequest excluding(Set<ProviderRef> alreadyTried) {
        return new RoutingRequest(message, segments, rotation, alreadyTried);
    }
}
