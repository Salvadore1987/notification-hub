package uz.hamkorbank.commhub.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.RouteEvaluationView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.ConfigMapper;
import uz.hamkorbank.commhub.application.port.in.EvaluateRoute;
import uz.hamkorbank.commhub.application.port.in.query.RouteEvaluationQuery;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.service.support.MessageRouting;
import uz.hamkorbank.commhub.application.service.support.RoutingOutcome;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.service.RoutingResult;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * "Which route would this message get?" without sending it (FR-8.9 groundwork, §11.2).
 *
 * <p>The value of a dry run comes from using the real thing: the query is turned into a transient
 * {@code Message} and handed to the same {@code MessageRouting} a submission uses, against the same
 * configuration snapshot. A separate "simulation" of the routing rules would be a second implementation
 * to keep in step, and the first time it drifted it would be lying at exactly the moment an operator
 * trusted it.
 *
 * <p>The message is never stored, never claimed against the dedup registry and never counted in a quota.
 * The delivery filters are deliberately not applied either: suppression and quiet hours answer "may this
 * recipient be contacted now", which is a different question from "where would this go".
 */
@Service
public class RouteEvaluationService implements EvaluateRoute {

    /** Body used when the query carries no text; one segment, which is the common case (§18.3). */
    private static final String PREVIEW_TEXT = "route preview";

    private static final ExternalMessageId DRY_RUN_ID = ExternalMessageId.of("route-dry-run");

    private final StreamRepository streams;
    private final MessageRouting routing;
    private final ClockPort clock;
    private final ConfigMapper mapper;

    public RouteEvaluationService(
            StreamRepository streams, MessageRouting routing, ClockPort clock, ConfigMapper mapper) {
        this.streams = Guard.notNull(streams, "streams");
        this.routing = Guard.notNull(routing, "routing");
        this.clock = Guard.notNull(clock, "clock");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public RouteEvaluationView evaluate(RouteEvaluationQuery query) {
        Guard.notNull(query, "query");
        Stream stream = streams.findById(query.streamId())
                .orElseThrow(
                        () -> NotFoundException.of("stream", query.streamId().value()));
        Message message = preview(query, stream);
        RoutingOutcome outcome = routing.route(message, Set.of());
        if (!outcome.isRouted()) {
            RoutingResult.NoRoute noRoute = outcome.noRoute().orElseThrow();
            return mapper.toView(noRoute, outcome.segments());
        }
        RoutingResult.Routed routed = outcome.requireRouted();
        return mapper.toView(
                routed,
                outcome.segments(),
                routing.costOf(outcome, routed.provider(), outcome.segments()).orElse(null));
    }

    /** Builds the throwaway message the router decides on; it applies the stream defaults (TC-02). */
    private Message preview(RouteEvaluationQuery query, Stream stream) {
        TrafficClass trafficClass = stream.effectiveTrafficClass(query.trafficClass());
        Priority priority = stream.effectivePriority(query.priority(), trafficClass);
        MessageEnvelope envelope =
                MessageEnvelope.single(stream.id(), DRY_RUN_ID, trafficClass).withPriority(priority);
        MessageContents contents = contentsFor(query, stream);
        return Message.accept(
                envelope, query.recipient(), planFor(query, contents), contents, null, Timing.immediate(), clock.now());
    }

    /**
     * Content for the channels the evaluation covers.
     *
     * <p>Only the shape matters — the router looks at the channel and, for least-cost routing, at the
     * segment count of the SMS text (§18.3, FR-2.3).
     */
    private static MessageContents contentsFor(RouteEvaluationQuery query, Stream stream) {
        List<Channel> channels = query.channelOptional().map(List::of).orElseGet(() -> stream.defaults()
                .channelOptional()
                .map(List::of)
                .orElseGet(() -> query.recipient().reachableChannels()));
        List<MessageContent> contents = new ArrayList<>();
        channels.forEach(channel -> contents.add(contentOf(channel, query.text())));
        return MessageContents.of(contents.toArray(new MessageContent[0]));
    }

    private static MessageContent contentOf(Channel channel, String text) {
        String body = text == null || text.isBlank() ? PREVIEW_TEXT : text;
        return switch (channel) {
            case SMS -> SmsContent.of(body);
            case EMAIL -> EmailContent.ofText(PREVIEW_TEXT, body);
            case PUSH -> PushContent.of(PREVIEW_TEXT, body);
        };
    }

    private static ChannelPlan planFor(RouteEvaluationQuery query, MessageContents contents) {
        return query.channelOptional()
                .map(ChannelPlan::explicitChannel)
                .orElseGet(() -> ChannelPlan.moduleChoice(List.copyOf(contents.channels())));
    }
}
