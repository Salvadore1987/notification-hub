package uz.hamkorbank.commhub.application.service.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.QuotaCounterPort;
import uz.hamkorbank.commhub.application.port.out.QuotaScope;
import uz.hamkorbank.commhub.application.port.out.QuotaWindow;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Count and cost quotas of the stream, the channel and the provider (FR-2.6).
 *
 * <p>The decision itself belongs to the domain — {@link QuotaConfig#evaluate} — this stage only reads
 * the day and month counters of every configured dimension, alerts when a limit is reached and turns a
 * {@code BLOCKED} verdict into a rejection with {@code QUOTA_EXCEEDED} (IR-01, OBS-04).
 *
 * <p>The dimensions are checked in order of how much they cost to breach: the stream first (its owner
 * is the one who pays), then the channel, then the provider. The first blocking verdict wins, and the
 * message is rejected naming the dimension — "quota exceeded" without saying whose is a support ticket.
 *
 * <p>{@link #check} and {@link #register} walk exactly the same scopes. They have to: a counter written
 * under one scope and read under another is a quota that never fires.
 */
@Component
public class QuotaGuard {

    private final QuotaCounterPort counters;
    private final MetricsPort metrics;

    public QuotaGuard(QuotaCounterPort counters, MetricsPort metrics) {
        this.counters = Guard.notNull(counters, "counters");
        this.metrics = Guard.notNull(metrics, "metrics");
    }

    /**
     * Checks every configured quota of the subject for one more send.
     *
     * @param units messages or SMS segments about to be sent; segments drive the cost (MP-06)
     * @param cost expected cost by the tariff of the chosen provider; {@code null} when unknown
     */
    public PipelineVerdict check(QuotaSubject subject, long units, Money cost, Instant now) {
        Guard.notNull(subject, "subject");
        Guard.notNull(now, "now");
        for (Dimension dimension : dimensionsOf(subject)) {
            QuotaVerdict verdict = evaluate(dimension, units, cost, now);
            if (verdict.requiresAlert()) {
                metrics.quotaBreached(subject.stream().id(), subject.channel(), verdict);
            }
            if (!verdict.permitsSending()) {
                return PipelineVerdict.rejected(
                        RejectionReason.QUOTA_EXCEEDED, "quota of %s is exhausted".formatted(dimension.name()));
            }
        }
        return PipelineVerdict.passed();
    }

    /** Registers an accepted send against every dimension that has a quota (FR-2.6). */
    public void register(QuotaSubject subject, long units, Money cost, Instant now) {
        Guard.notNull(subject, "subject");
        dimensionsOf(subject).forEach(dimension -> counters.register(dimension.scope(), units, cost, now));
    }

    private QuotaVerdict evaluate(Dimension dimension, long units, Money cost, Instant now) {
        return dimension
                .quota()
                .evaluate(
                        counters.usage(dimension.scope(), QuotaWindow.DAY, now),
                        counters.usage(dimension.scope(), QuotaWindow.MONTH, now),
                        units,
                        cost);
    }

    /**
     * Dimensions worth touching for this send.
     *
     * <p>An unlimited quota is skipped entirely rather than evaluated to "allowed": each dimension
     * costs two counter reads, and on the OTP path those are two round trips that buy nothing (TC-01).
     */
    private static List<Dimension> dimensionsOf(QuotaSubject subject) {
        List<Dimension> dimensions = new ArrayList<>(3);
        add(
                dimensions,
                "stream " + subject.stream().id().value(),
                subject.stream().quota(),
                QuotaScope.ofStream(subject.stream().id()));
        subject.channelConfigOptional()
                .ifPresent(config -> add(
                        dimensions,
                        "channel " + subject.channel(),
                        config.quota(),
                        QuotaScope.ofChannel(subject.channel())));
        subject.providerOptional()
                .ifPresent(provider -> add(
                        dimensions,
                        "provider " + provider.code().value(),
                        provider.quota(),
                        QuotaScope.ofProvider(subject.channel(), provider.id())));
        return dimensions;
    }

    private static void add(List<Dimension> dimensions, String name, QuotaConfig quota, QuotaScope scope) {
        if (quota != null && !quota.isUnlimited()) {
            dimensions.add(new Dimension(name, quota, scope));
        }
    }

    /** One configured quota together with the counter scope it is measured in. */
    private record Dimension(String name, QuotaConfig quota, QuotaScope scope) {}
}
