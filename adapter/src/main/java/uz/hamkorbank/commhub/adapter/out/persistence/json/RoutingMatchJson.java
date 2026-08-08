package uz.hamkorbank.commhub.adapter.out.persistence.json;

import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** {@link RoutingPolicy.Match} inside {@code routing_policy.match}; a {@code null} field matches anything. */
public record RoutingMatchJson(String streamId, String trafficClass, String minPriority, String channel) {

    public static RoutingMatchJson of(RoutingPolicy.Match match) {
        return new RoutingMatchJson(
                match.streamId() == null ? null : match.streamId().value(),
                match.trafficClass() == null ? null : match.trafficClass().name(),
                match.minPriority() == null ? null : match.minPriority().name(),
                match.channel() == null ? null : match.channel().name());
    }

    public RoutingPolicy.Match toDomain() {
        return new RoutingPolicy.Match(
                streamId == null ? null : StreamId.of(streamId),
                trafficClass == null ? null : TrafficClass.valueOf(trafficClass),
                minPriority == null ? null : Priority.valueOf(minPriority),
                channel == null ? null : Channel.valueOf(channel));
    }
}
