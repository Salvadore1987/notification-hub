package uz.hamkorbank.commhub.application.port.out.provider;

import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Envelope attributes every provider adapter needs, independent of the channel (MP-01).
 *
 * <p>Adapters map {@link #priority()} onto their own scale — Playmobile {@code low|normal|high|
 * realtime} (PM-03), SMS Gate {@code weight} 0–10 (SG-01), APNs {@code apns-priority} (PU-06) — and
 * propagate {@link #correlationId()} into their request headers and logs (FR-8.6, OBS-02).
 *
 * @param test configuration test send: excluded from business statistics (FR-7.4, PU-13)
 */
public record SubmissionContext(
        TrafficClass trafficClass, Priority priority, CorrelationId correlationId, boolean test) {

    public SubmissionContext {
        Guard.notNull(trafficClass, "SubmissionContext.trafficClass");
        Guard.notNull(priority, "SubmissionContext.priority");
        Guard.notNull(correlationId, "SubmissionContext.correlationId");
    }
}
