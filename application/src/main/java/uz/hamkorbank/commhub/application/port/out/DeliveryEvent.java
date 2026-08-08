package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One finished send, as the Bank's data mart receives it (FR-6.4).
 *
 * <p>The unit of the feed is a message that reached a terminal status, not a status transition. A mart
 * answers "how much did we send, through whom, at what cost, and did it arrive" — questions with one
 * row per message — and the transition-level stream already exists for anyone who needs it
 * ({@code comm.outbound.status.v1}). Exporting both would be the same traffic twice in two shapes.
 *
 * <p>There is no recipient here and no content. What leaves the Bank's sending contour for its
 * analytical one is what was sent, not to whom (SEC-06, DB-04); the message id is the join key for
 * anyone entitled to more.
 *
 * @param test the flag of FR-7.4, so the mart can exclude configuration checks from business figures
 */
public record DeliveryEvent(
        MessageId messageId,
        StreamId streamId,
        BatchId batchId,
        TrafficClass trafficClass,
        Channel channel,
        ProviderCode provider,
        DeliveryOutcome outcome,
        boolean test) {

    public DeliveryEvent {
        Guard.notNull(messageId, "DeliveryEvent.messageId");
        Guard.notNull(streamId, "DeliveryEvent.streamId");
        Guard.notNull(trafficClass, "DeliveryEvent.trafficClass");
        Guard.notNull(outcome, "DeliveryEvent.outcome");
    }

    /**
     * How the message ended (§6.3).
     *
     * @param reason set for every non-delivery status (ST-01)
     * @param segments SMS segments actually charged (§18.3); 0 on the other channels
     * @param cost expected cost by the provider's tariff (FR-6.2); {@code null} when no tariff applies
     * @param attempts number of provider attempts, including failovers (PR-01)
     * @param terminalAt moment the message reached this status; the ordering key of the export
     */
    public record DeliveryOutcome(
            MessageStatus status,
            RejectionReason reason,
            int segments,
            Money cost,
            int attempts,
            Instant acceptedAt,
            Instant terminalAt) {

        public DeliveryOutcome {
            Guard.notNull(status, "DeliveryOutcome.status");
            Guard.notNull(acceptedAt, "DeliveryOutcome.acceptedAt");
            Guard.notNull(terminalAt, "DeliveryOutcome.terminalAt");
        }
    }
}
