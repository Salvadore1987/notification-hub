package uz.hamkorbank.commhub.application.port.out;

import java.time.Duration;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * Business metrics of the pipeline (OBS-01, FR-6.1).
 *
 * <p>Implemented by a Micrometer adapter; the core stays free of any metrics library. Parameters may
 * be {@code null} where the value is not known yet, e.g. the channel before routing.
 *
 * <p>Message-level counters carry the {@code test} flag of FR-7.4 rather than dropping test sends: a
 * configuration check has to stay visible to the operator who ran it, and "excluded from business
 * statistics" is a dimension the dashboards and the alert rules filter on, not data thrown away.
 */
public interface MetricsPort {

    /** A submission was accepted for processing (FR-1.1); {@code test} keeps it out of the business figures. */
    void messageAccepted(StreamId streamId, TrafficClass trafficClass, Channel channel, boolean test);

    /** A submission was rejected; the reason becomes a metric tag (IR-01, OBS-04). */
    void messageRejected(StreamId streamId, RejectionReason reason);

    /** A submission was suppressed by the idempotency check (FR-1.5). */
    void messageDuplicate(StreamId streamId);

    /** A canonical status change of a message (§6.3, OBS-01); {@code test} marks a configuration check. */
    void statusChanged(MessageStatus status, Channel channel, ProviderRef provider, boolean test);

    /** One provider call with its outcome and latency (PR-03, FR-6.3). */
    void providerCall(ProviderRef provider, AttemptResult result, Duration latency);

    /** A count or cost quota was reached (FR-2.6, OBS-04). */
    void quotaBreached(StreamId streamId, Channel channel, QuotaVerdict verdict);

    /** The frequency cap of a recipient was exceeded (FR-5.4). */
    void frequencyCapExceeded(Channel channel, long observed, long limit);

    /**
     * Content carrying a full card number was found (SEC-05).
     *
     * @param blocked whether the message was rejected; {@code false} in the alert-only mode of SEC-05,
     *     and an alert that fires without blocking is exactly the one operations has to act on
     */
    void panDetected(Channel channel, boolean blocked);

    /** An address was added to the suppression list without an operator asking (FR-5.1, EM-02). */
    void recipientSuppressed(Channel channel, SuppressionReason reason);

    /** Duration of a pipeline stage: accept, template, route, dispatch (OBS-01, TC-01). */
    void stageLatency(String stage, TrafficClass trafficClass, Duration duration);
}
