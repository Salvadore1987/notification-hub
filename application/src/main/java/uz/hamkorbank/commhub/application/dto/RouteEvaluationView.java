package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The route a hypothetical message would get (FR-8.9 groundwork).
 *
 * <p>Either a decision — channel, chosen provider, the reserves behind it, the strategy that produced
 * it and the expected cost — or the canonical reason why there is none. The same two outcomes a real
 * submission gets, which is the point of a dry run.
 *
 * @param segments SMS segments the text would be split into; 0 for other channels (§18.3)
 */
public record RouteEvaluationView(
        boolean routed,
        Channel channel,
        ProviderCode provider,
        List<ProviderCode> fallbackProviders,
        BalancingStrategy strategy,
        int segments,
        Money estimatedCost,
        Rejection rejection) {

    public RouteEvaluationView {
        fallbackProviders = Guard.copyOf(fallbackProviders);
        Guard.notNegative(segments, "RouteEvaluationView.segments");
        Guard.isTrue(routed == (rejection == null), "a route evaluation is either routed or rejected");
    }

    public Optional<Money> estimatedCostOptional() {
        return Optional.ofNullable(estimatedCost);
    }

    public Optional<Rejection> rejectionOptional() {
        return Optional.ofNullable(rejection);
    }

    /** Why no route is available (IR-01). */
    public record Rejection(RejectionReason reason, String detail) {

        public Rejection {
            Guard.notNull(reason, "Rejection.reason");
        }
    }
}
