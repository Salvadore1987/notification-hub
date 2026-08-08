package uz.hamkorbank.commhub.application.port.in.query;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * "Which route would message X get?" — the dry run of the routing configuration (FR-8.9 groundwork).
 *
 * <p>Answers the question an operator actually asks after editing a policy, and answers it without
 * sending anything: the query is turned into a transient message, run through the same {@code Router}
 * against the same configuration snapshot, and thrown away. Nothing is stored, no quota is consumed,
 * no counter moves.
 *
 * @param channel channel to force; {@code null} lets the module choose as it would for a real message
 * @param text SMS body; only its length matters, for the segment count least-cost routing needs
 *     (§18.3). {@code null} falls back to a one-segment message
 */
public record RouteEvaluationQuery(
        StreamId streamId,
        Recipient recipient,
        Channel channel,
        TrafficClass trafficClass,
        Priority priority,
        String text) {

    public RouteEvaluationQuery {
        Guard.notNull(streamId, "RouteEvaluationQuery.streamId");
        Guard.notNull(recipient, "RouteEvaluationQuery.recipient");
    }

    public Optional<Channel> channelOptional() {
        return Optional.ofNullable(channel);
    }
}
