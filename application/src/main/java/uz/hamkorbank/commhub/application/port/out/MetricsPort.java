package uz.hamkorbank.commhub.application.port.out;

import java.time.Duration;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * Business metrics of the pipeline (OBS-01, FR-6.1).
 *
 * <p>Implemented by a Micrometer adapter; the core stays free of any metrics library. Parameters may
 * be {@code null} where the value is not known yet, e.g. the channel before routing.
 */
public interface MetricsPort {

    /** A submission was accepted for processing (FR-1.1). */
    void messageAccepted(StreamId streamId, TrafficClass trafficClass, Channel channel);

    /** A submission was rejected; the reason becomes a metric tag (IR-01, OBS-04). */
    void messageRejected(StreamId streamId, RejectionReason reason);

    /** A submission was suppressed by the idempotency check (FR-1.5). */
    void messageDuplicate(StreamId streamId);

    /** A canonical status change of a message (§6.3, OBS-01). */
    void statusChanged(MessageStatus status, Channel channel, ProviderRef provider);

    /** One provider call with its outcome and latency (PR-03, FR-6.3). */
    void providerCall(ProviderRef provider, AttemptResult result, Duration latency);

    /** A count or cost quota was reached (FR-2.6, OBS-04). */
    void quotaBreached(StreamId streamId, Channel channel, QuotaVerdict verdict);

    /** The frequency cap of a recipient was exceeded (FR-5.4). */
    void frequencyCapExceeded(Channel channel, long observed, long limit);

    /** Duration of a pipeline stage: accept, template, route, dispatch (OBS-01, TC-01). */
    void stageLatency(String stage, TrafficClass trafficClass, Duration duration);
}
