package uz.hamkorbank.commhub.adapter.out.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * {@link MetricsPort} over Micrometer (OBS-01).
 *
 * <p>The application layer has no metrics library on its classpath and no logger either, so this adapter
 * is the only place where "something worth reporting happened" becomes an observable number. Everything
 * it exports is a counter or a timer keyed by the closed enums of the domain — the port hands over value
 * objects, never free text, which is what keeps the cardinality bounded.
 *
 * <p>Latency meters publish a percentile histogram rather than pre-computed percentiles: the OTP SLA of
 * TC-01 is a p99 over a whole deployment, and percentiles that were already computed per instance cannot
 * be aggregated. The histogram can, at the cost of a few more series.
 *
 * <p>Null dimensions are expected and not defects — a message rejected before routing has no channel and
 * no provider — so they become the tag value {@code none} instead of being dropped, which keeps a
 * rejection visible in the same query as an acceptance.
 */
@Component
public class MicrometerMetricsAdapter implements MetricsPort {

    private final MeterRegistry registry;

    public MicrometerMetricsAdapter(MeterRegistry registry) {
        this.registry = Guard.notNull(registry, "registry");
    }

    @Override
    public void messageAccepted(StreamId streamId, TrafficClass trafficClass, Channel channel, boolean test) {
        registry.counter(
                        MetricNames.MESSAGES_ACCEPTED,
                        Tags.of(
                                MetricNames.TAG_STREAM, streamOf(streamId),
                                MetricNames.TAG_TRAFFIC_CLASS, nameOf(trafficClass),
                                MetricNames.TAG_CHANNEL, nameOf(channel),
                                MetricNames.TAG_TEST, Boolean.toString(test)))
                .increment();
    }

    @Override
    public void messageRejected(StreamId streamId, RejectionReason reason) {
        registry.counter(
                        MetricNames.MESSAGES_REJECTED,
                        Tags.of(
                                MetricNames.TAG_STREAM, streamOf(streamId),
                                MetricNames.TAG_REASON, nameOf(reason)))
                .increment();
    }

    @Override
    public void messageDuplicate(StreamId streamId) {
        registry.counter(MetricNames.MESSAGES_DUPLICATE, Tags.of(MetricNames.TAG_STREAM, streamOf(streamId)))
                .increment();
    }

    @Override
    public void statusChanged(MessageStatus status, Channel channel, ProviderRef provider, boolean test) {
        registry.counter(
                        MetricNames.MESSAGE_STATUS,
                        Tags.of(
                                MetricNames.TAG_STATUS, nameOf(status),
                                MetricNames.TAG_CHANNEL, nameOf(channel),
                                MetricNames.TAG_PROVIDER, providerOf(provider),
                                MetricNames.TAG_TEST, Boolean.toString(test)))
                .increment();
    }

    @Override
    public void providerCall(ProviderRef provider, AttemptResult result, Duration latency) {
        Tags tags = Tags.of(
                MetricNames.TAG_PROVIDER, providerOf(provider),
                MetricNames.TAG_CHANNEL, provider == null ? MetricNames.NONE : nameOf(provider.channel()),
                MetricNames.TAG_RESULT, nameOf(result));
        timer(MetricNames.PROVIDER_CALLS, tags).record(latency == null ? Duration.ZERO : latency);
    }

    @Override
    public void quotaBreached(StreamId streamId, Channel channel, QuotaVerdict verdict) {
        registry.counter(
                        MetricNames.QUOTA_BREACHED,
                        Tags.of(
                                MetricNames.TAG_STREAM, streamOf(streamId),
                                MetricNames.TAG_CHANNEL, nameOf(channel),
                                MetricNames.TAG_VERDICT, nameOf(verdict)))
                .increment();
    }

    /**
     * Counts the event, not the numbers behind it: {@code observed} is a property of one recipient and
     * {@code limit} is configuration already visible in the channel card. What operations watches is how
     * often the cap fires per channel (FR-5.4).
     */
    @Override
    public void frequencyCapExceeded(Channel channel, long observed, long limit) {
        registry.counter(MetricNames.FREQUENCY_CAP_EXCEEDED, Tags.of(MetricNames.TAG_CHANNEL, nameOf(channel)))
                .increment();
    }

    @Override
    public void panDetected(Channel channel, boolean blocked) {
        registry.counter(
                        MetricNames.PAN_DETECTED,
                        Tags.of(
                                MetricNames.TAG_CHANNEL, nameOf(channel),
                                MetricNames.TAG_BLOCKED, Boolean.toString(blocked)))
                .increment();
    }

    @Override
    public void recipientSuppressed(Channel channel, SuppressionReason reason) {
        registry.counter(
                        MetricNames.RECIPIENTS_SUPPRESSED,
                        Tags.of(
                                MetricNames.TAG_CHANNEL, nameOf(channel),
                                MetricNames.TAG_REASON, nameOf(reason)))
                .increment();
    }

    @Override
    public void stageLatency(String stage, TrafficClass trafficClass, Duration duration) {
        timer(
                        MetricNames.PIPELINE_STAGE,
                        Tags.of(
                                MetricNames.TAG_STAGE,
                                stage == null || stage.isBlank() ? MetricNames.NONE : stage,
                                MetricNames.TAG_TRAFFIC_CLASS,
                                nameOf(trafficClass)))
                .record(duration == null ? Duration.ZERO : duration);
    }

    private Timer timer(String name, Tags tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().register(registry);
    }

    private static String streamOf(StreamId streamId) {
        return streamId == null ? MetricNames.NONE : streamId.value();
    }

    private static String providerOf(ProviderRef provider) {
        return provider == null ? MetricNames.NONE : provider.code().value();
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? MetricNames.NONE : value.name();
    }
}
