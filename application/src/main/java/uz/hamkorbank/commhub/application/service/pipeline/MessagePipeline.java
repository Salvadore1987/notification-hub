package uz.hamkorbank.commhub.application.service.pipeline;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.service.support.MessageRouting;
import uz.hamkorbank.commhub.application.service.support.RoutingOutcome;
import uz.hamkorbank.commhub.application.service.support.SuppressionRegistrar;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The single, channel-agnostic pipeline of the Hub (SRS §5.1): deduplication → templating →
 * validation → routing → delivery filters → quotas.
 *
 * <p>One entry point for the use cases so that a message, a batch item and a DLQ retry all traverse
 * exactly the same stages in exactly the same order. Every stage answers with a verdict record; none
 * of them mutates the message, which keeps the status transitions in the hands of the use case
 * (ST-01).
 */
@Component
public class MessagePipeline {

    private final DeduplicationService deduplication;
    private final TemplateApplier templates;
    private final MessageValidator validator;
    private final DeliveryFilters filters;
    private final QuotaGuard quotas;
    private final MessageRouting routing;
    private final SuppressionRegistrar suppressions;

    public MessagePipeline(
            DeduplicationService deduplication,
            TemplateApplier templates,
            MessageValidator validator,
            DeliveryFilters filters,
            QuotaGuard quotas,
            MessageRouting routing,
            SuppressionRegistrar suppressions) {
        this.deduplication = Guard.notNull(deduplication, "deduplication");
        this.templates = Guard.notNull(templates, "templates");
        this.validator = Guard.notNull(validator, "validator");
        this.filters = Guard.notNull(filters, "filters");
        this.quotas = Guard.notNull(quotas, "quotas");
        this.routing = Guard.notNull(routing, "routing");
        this.suppressions = Guard.notNull(suppressions, "suppressions");
    }

    // --- Deduplication (FR-1.5) -------------------------------------------------

    public DedupKey dedupKeyOf(DedupKey explicitKey, StreamId streamId, ExternalMessageId externalMessageId) {
        return deduplication.keyOf(explicitKey, streamId, externalMessageId);
    }

    /** Cheap pre-check against the dedup window. */
    public Optional<MessageId> findDuplicate(DedupKey key, Instant now) {
        return deduplication.findOriginal(key, now);
    }

    /** Atomic claim of the key; a non-empty answer means another submission won the race. */
    public Optional<MessageId> claimDedupKey(DedupKey key, MessageId messageId, Instant now) {
        return deduplication.claim(key, messageId, now);
    }

    // --- Templating (FR-4.1, FR-4.3) --------------------------------------------

    public TemplateOutcome applyTemplate(MessageContents contents, TemplateRef ref, ContentLocale locale) {
        return templates.apply(contents, ref, locale);
    }

    // --- Validation (FR-1.4, PU-11, SEC-05) -------------------------------------

    public PipelineVerdict validate(Message message) {
        return validator.validate(message);
    }

    // --- Routing (MP-05, FR-2.2, FR-2.3) ----------------------------------------

    public RoutingOutcome route(Message message, Set<ProviderRef> excludedProviders) {
        return routing.route(message, excludedProviders);
    }

    /** Routes onto one named provider — the configuration test send of FR-7.4 and nothing else. */
    public RoutingOutcome routeTo(Message message, ProviderRef pinnedProvider) {
        return routing.routeTo(message, pinnedProvider);
    }

    public Optional<Money> costOf(RoutingOutcome outcome, ProviderRef provider, int segments) {
        return routing.costOf(outcome, provider, segments);
    }

    // --- Delivery filters (FR-5.1…FR-5.4) ---------------------------------------

    public FilterVerdict filter(Message message, Channel channel, Stream stream, ChannelConfig config, Instant now) {
        return filters.apply(message, channel, stream, config, now);
    }

    /**
     * Adds the recipient's address to the suppression list because the provider called it unusable
     * (FR-5.1, EM-02, §18.2, PU-04).
     *
     * <p>Here rather than injected into the sending saga directly: the suppression list is the filters' own
     * data, and the pipeline is the one door the use cases use to reach the filter stages. The next send to
     * that address is then stopped by {@link #filter} without the saga having to remember anything.
     */
    public void suppress(Message message, Channel channel, SuppressionReason reason, Actor actor, Instant now) {
        suppressions.suppress(message, channel, reason, actor, now);
    }

    // --- Quotas (FR-2.6) --------------------------------------------------------

    /** Quota subject of a routed message: its stream, channel and chosen provider (FR-2.6). */
    public QuotaSubject quotaSubjectOf(Stream stream, Channel channel, RoutingOutcome outcome, ProviderRef provider) {
        return new QuotaSubject(
                stream,
                channel,
                outcome.channelConfig(channel).orElse(null),
                outcome.configuration().provider(provider.id()).orElse(null));
    }

    public PipelineVerdict checkQuota(QuotaSubject subject, long units, Money cost, Instant now) {
        return quotas.check(subject, units, cost, now);
    }

    /** Registers an accepted send against the quota and frequency counters (FR-2.6, FR-5.4). */
    public void registerSend(Message message, QuotaSubject subject, long units, Money cost, Instant now) {
        quotas.register(subject, units, cost, now);
        filters.registerSend(message.recipient(), subject.channel(), now);
    }
}
