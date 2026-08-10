package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What the operator is shown before a panel-initiated send is confirmed (ADR-0038, FR-4.4).
 *
 * <p>Everything here comes from the real machinery — the real {@code SegmentCalculator}, the real
 * {@code Router} over the real configuration snapshot — because a number produced by a second, simpler
 * implementation would be reassuring and wrong.
 *
 * @param missingVariables merge fields no row filled in; those rows would come back as
 *     {@code TEMPLATE_VARIABLE_MISSING} rejections, and finding that out after the button is pressed is
 *     the failure this step exists to prevent
 * @param rejection why nothing can be sent at all, when that is the answer; the confirm button stays
 *     disabled in that case
 */
public record SendEstimateView(
        Channel channel,
        TemplateVersionRef template,
        long recipients,
        long segments,
        Money estimatedCost,
        ProviderCode provider,
        List<String> missingVariables,
        RouteEvaluationView.Rejection rejection) {

    public SendEstimateView {
        Guard.notNull(channel, "SendEstimateView.channel");
        Guard.notNegative(recipients, "SendEstimateView.recipients");
        Guard.notNegative(segments, "SendEstimateView.segments");
        missingVariables = Guard.copyOf(missingVariables);
    }

    public Optional<Money> estimatedCostOptional() {
        return Optional.ofNullable(estimatedCost);
    }

    public Optional<ProviderCode> providerOptional() {
        return Optional.ofNullable(provider);
    }

    public Optional<RouteEvaluationView.Rejection> rejectionOptional() {
        return Optional.ofNullable(rejection);
    }

    /** Whether the send may proceed at all. */
    public boolean isSendable() {
        return rejection == null;
    }

    /** The exact version priced here, so the screen can name what it is about to send (FR-4.2). */
    public record TemplateVersionRef(int number, TemplateStatus status) {}
}
