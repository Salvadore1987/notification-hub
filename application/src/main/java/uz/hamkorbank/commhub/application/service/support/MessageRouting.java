package uz.hamkorbank.commhub.application.service.support;

import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.service.Router;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;
import uz.hamkorbank.commhub.domain.service.RoutingRequest;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Drives the domain {@code Router} with everything it needs from the outside (MP-05, FR-2.3, AD-07).
 *
 * <p>The router is a pure function of the message plus a configuration snapshot; this component
 * fetches the snapshot for the submitting stream, computes the SMS segment count that least-cost
 * routing needs (MP-06) and supplies the rotation position that makes round-robin deterministic
 * without giving the router any state (AR-07).
 */
@Component
public class MessageRouting {

    private final Router router;
    private final SegmentCalculator segmentCalculator;
    private final ProviderConfigRepository providerConfig;
    private final RoutingRotation rotation;

    public MessageRouting(
            Router router,
            SegmentCalculator segmentCalculator,
            ProviderConfigRepository providerConfig,
            RoutingRotation rotation) {
        this.router = Guard.notNull(router, "router");
        this.segmentCalculator = Guard.notNull(segmentCalculator, "segmentCalculator");
        this.providerConfig = Guard.notNull(providerConfig, "providerConfig");
        this.rotation = Guard.notNull(rotation, "rotation");
    }

    /**
     * Routes a message, skipping providers that were already tried (FR-2.2, FR-6.3).
     *
     * @param excluded providers of previous attempts; empty on the first routing
     */
    public RoutingOutcome route(Message message, Set<ProviderRef> excluded) {
        Guard.notNull(message, "message");
        RoutingConfiguration configuration =
                providerConfig.routingConfiguration(message.envelope().streamId());
        int segments = segmentsOf(message);
        RoutingRequest request = new RoutingRequest(
                message, segments, rotation.next(preferredChannel(message)), excluded == null ? Set.of() : excluded);
        return new RoutingOutcome(router.route(request, configuration), configuration, segments);
    }

    /** Segment count of the SMS content of the message, 0 when it carries none (MP-06, §18.3). */
    public int segmentsOf(Message message) {
        return message.contents()
                .forChannel(Channel.SMS)
                .map(SmsContent.class::cast)
                .map(content -> segmentCalculator.calculate(content).segments())
                .orElse(0);
    }

    /** Expected cost of the message on the chosen provider, by its tariff (FR-2.6, FR-6.2). */
    public Optional<Money> costOf(RoutingOutcome outcome, ProviderRef provider, int segments) {
        Guard.notNull(outcome, "outcome");
        Guard.notNull(provider, "provider");
        return outcome.configuration().provider(provider.id()).flatMap(configured -> configured.costOf(segments));
    }

    /** Channel the rotation counter is kept for; the plan decides, otherwise the first reachable one. */
    private static Channel preferredChannel(Message message) {
        return message.channelPlan().primaryChannel().orElseGet(() -> message.deliverableChannels().stream()
                .findFirst()
                .orElse(Channel.SMS));
    }
}
