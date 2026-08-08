package uz.hamkorbank.commhub.application.service.pipeline;

import java.time.Instant;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.QuotaCounterPort;
import uz.hamkorbank.commhub.application.port.out.QuotaScope;
import uz.hamkorbank.commhub.application.port.out.QuotaWindow;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Count and cost quotas of a stream (FR-2.6).
 *
 * <p>The decision itself belongs to the domain — {@link QuotaConfig#evaluate} — this stage only reads
 * the day and month counters, alerts when a limit is reached and turns a {@code BLOCKED} verdict into
 * a rejection with {@code QUOTA_EXCEEDED} (IR-01, OBS-04).
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
     * Checks the quotas of the stream for one more send.
     *
     * @param units messages or SMS segments about to be sent; segments drive the cost (MP-06)
     * @param cost expected cost by the tariff of the chosen provider; {@code null} when unknown
     */
    public PipelineVerdict check(Stream stream, Channel channel, long units, Money cost, Instant now) {
        Guard.notNull(stream, "stream");
        Guard.notNull(now, "now");
        QuotaConfig quota = stream.quota();
        if (quota.isUnlimited()) {
            return PipelineVerdict.passed();
        }
        QuotaScope scope = QuotaScope.ofStream(stream.id());
        QuotaVerdict verdict = quota.evaluate(
                counters.usage(scope, QuotaWindow.DAY, now),
                counters.usage(scope, QuotaWindow.MONTH, now),
                units,
                cost);
        if (verdict.requiresAlert()) {
            metrics.quotaBreached(stream.id(), channel, verdict);
        }
        if (verdict.permitsSending()) {
            return PipelineVerdict.passed();
        }
        return PipelineVerdict.rejected(
                RejectionReason.QUOTA_EXCEEDED, "quota of stream %s is exhausted".formatted(stream.id()));
    }

    /** Registers a send against the counters of the stream (FR-2.6). */
    public void register(Stream stream, Channel channel, long units, Money cost, Instant now) {
        Guard.notNull(stream, "stream");
        counters.register(QuotaScope.ofStreamChannel(stream.id(), channel), units, cost, now);
    }
}
