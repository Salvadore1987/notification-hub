package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.service.SmsSegmentation;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What a template will look like and cost before anything is sent (FR-4.4).
 *
 * <p>Answers the question an operator writing an SMS actually has: how many segments this wording takes
 * and what a send costs at today's provider tariffs. Segments are computed by the same
 * {@code SegmentCalculator} the pipeline bills with (§18.3, MP-06), so the preview and the invoice agree.
 *
 * @param rendered subject and text after substitution, exactly as the provider would receive them
 * @param missingVariables merge fields the previewed variables have no value for; they stay visible as
 *     {@code {NAME}} in the rendered text instead of being silently dropped (FR-4.3)
 * @param costs price of one message per provider of the channel, cheapest first (FR-2.3, FR-6.2)
 * @param segmentation SMS segmentation; {@code null} for email and push, which are not segmented
 */
public record TemplatePreviewView(
        TemplateCode code,
        Channel channel,
        ContentLocale locale,
        Version version,
        TemplateVersion.Rendered rendered,
        List<String> missingVariables,
        List<ProviderCost> costs,
        SmsSegmentation segmentation) {

    public TemplatePreviewView {
        Guard.notNull(code, "TemplatePreviewView.code");
        Guard.notNull(channel, "TemplatePreviewView.channel");
        Guard.notNull(locale, "TemplatePreviewView.locale");
        Guard.notNull(version, "TemplatePreviewView.version");
        Guard.notNull(rendered, "TemplatePreviewView.rendered");
        missingVariables = Guard.copyOf(missingVariables);
        costs = Guard.copyOf(costs);
    }

    public Optional<SmsSegmentation> segmentationOptional() {
        return Optional.ofNullable(segmentation);
    }

    /**
     * Which version was previewed (FR-4.1, FR-4.4).
     *
     * <p>The status is part of the answer: a preview is most useful on a draft, and the screen must not
     * let an operator believe that what they are looking at is what customers are getting.
     */
    public record Version(int number, TemplateStatus status) {

        public Version {
            Guard.positive(number, "Version.number");
            Guard.notNull(status, "Version.status");
        }
    }

    /**
     * Cost of one message on one provider at its current tariff (FR-4.4, FR-6.2).
     *
     * @param selectable whether the router could pick this provider right now — a cheaper provider that
     *     is disabled or {@code DOWN} is not the price the send will actually pay (FR-2.7, PR-02)
     */
    public record ProviderCost(ProviderCode providerCode, Money cost, boolean selectable) {

        public ProviderCost {
            Guard.notNull(providerCode, "ProviderCost.providerCode");
            Guard.notNull(cost, "ProviderCost.cost");
        }
    }
}
