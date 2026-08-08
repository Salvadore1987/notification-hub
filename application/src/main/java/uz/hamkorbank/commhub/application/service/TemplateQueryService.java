package uz.hamkorbank.commhub.application.service;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.TemplatePreviewView;
import uz.hamkorbank.commhub.application.dto.TemplateSummary;
import uz.hamkorbank.commhub.application.dto.TemplateView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.TemplateMapper;
import uz.hamkorbank.commhub.application.port.in.GetTemplates;
import uz.hamkorbank.commhub.application.port.in.query.TemplateListQuery;
import uz.hamkorbank.commhub.application.port.in.query.TemplatePreviewQuery;
import uz.hamkorbank.commhub.application.port.in.query.TemplateQuery;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;
import uz.hamkorbank.commhub.domain.service.SmsSegmentation;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Read side of the template catalogue and the SMS preview (FR-4.1, FR-4.4).
 *
 * <p>The preview costs the wording with the same two pieces the pipeline bills with — the domain
 * {@code SegmentCalculator} (§18.3) and the tariffs stored on the providers (FR-2.1) — for the reason the
 * routing dry run reuses the real router: a second implementation of "how many segments is this" would
 * disagree with the invoice exactly when someone relied on it.
 *
 * <p>Cost is quoted for every provider of the channel that has a tariff, cheapest first, with a flag
 * saying whether the router could actually pick it. A price from a disabled provider is not the price the
 * send will pay, and hiding those rows would leave an operator wondering why the cheap one never wins.
 */
@Service
public class TemplateQueryService implements GetTemplates {

    /**
     * Cheapest first, grouped by currency.
     *
     * <p>Grouped and not simply compared, because {@code Money} refuses to compare across currencies — and
     * rightly so. A preview must not be the place where a provider billing in a second currency throws an
     * exception at an operator who only wanted to read a price list.
     */
    private static final Comparator<TemplatePreviewView.ProviderCost> CHEAPEST_FIRST = Comparator.comparing(
                    (TemplatePreviewView.ProviderCost cost) ->
                            cost.cost().currency().getCurrencyCode())
            .thenComparing(cost -> cost.cost().amount());

    private final TemplateRepository templates;
    private final ProviderConfigRepository providers;
    private final SegmentCalculator segmentCalculator;
    private final TemplateMapper mapper;

    public TemplateQueryService(
            TemplateRepository templates,
            ProviderConfigRepository providers,
            SegmentCalculator segmentCalculator,
            TemplateMapper mapper) {
        this.templates = Guard.notNull(templates, "templates");
        this.providers = Guard.notNull(providers, "providers");
        this.segmentCalculator = Guard.notNull(segmentCalculator, "segmentCalculator");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public TemplateView find(TemplateQuery query) {
        Guard.notNull(query, "query");
        return mapper.toView(require(query));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TemplateSummary> list(TemplateListQuery query) {
        Guard.notNull(query, "query");
        return templates
                .findAll(query.channel(), query.direction(), query.catalogStatus(), query.limit(), query.offset())
                .stream()
                .map(mapper::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TemplatePreviewView preview(TemplatePreviewQuery query) {
        Guard.notNull(query, "query");
        Template template = require(query.code());
        TemplateVersion version = resolve(template, query);
        TemplateVersion.Rendered rendered = version.renderPreview(query.variables());
        SmsSegmentation segmentation =
                template.channel() == Channel.SMS ? segmentCalculator.calculate(rendered.text()) : null;
        int segments = segmentation == null ? 0 : segmentation.segments();
        return mapper.toPreview(
                template, version, query.variables(), costs(template.channel(), segments), segmentation);
    }

    /** Requested version, or the latest one of the locale — a preview must reach a draft (FR-4.4). */
    private static TemplateVersion resolve(Template template, TemplatePreviewQuery query) {
        return query.versionOptional()
                .map(number -> template.version(query.locale(), number)
                        .orElseThrow(() -> NotFoundException.of(
                                "template version",
                                "%s/%s/%d".formatted(template.code().value(), query.locale(), number))))
                .orElseGet(() -> template.latestVersion(query.locale())
                        .orElseThrow(() -> NotFoundException.of(
                                "template version",
                                "%s/%s".formatted(template.code().value(), query.locale()))));
    }

    /**
     * Price of one message on every provider of the channel that has a tariff (FR-4.4, FR-6.2).
     *
     * <p>A provider without a tariff is left out rather than quoted as free — the panel showing 0 next to a
     * provider whose price list nobody entered would be worse than showing nothing.
     */
    private List<TemplatePreviewView.ProviderCost> costs(Channel channel, int segments) {
        return providers.findProviders(channel).stream()
                .flatMap(provider -> provider.costOf(segments).stream()
                        .map(cost ->
                                new TemplatePreviewView.ProviderCost(provider.code(), cost, provider.isSelectable())))
                .sorted(CHEAPEST_FIRST)
                .toList();
    }

    private Template require(TemplateQuery query) {
        return require(query.code());
    }

    private Template require(TemplateCode code) {
        return templates.findByCode(code).orElseThrow(() -> NotFoundException.of("template", code.value()));
    }
}
