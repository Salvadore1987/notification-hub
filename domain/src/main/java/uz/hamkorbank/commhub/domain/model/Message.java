package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.content.MessageContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The unit of delivery: canonical, channel-independent message (SRS §5.2, §6.1, MP-01).
 *
 * <p>Aggregate root over the channel-independent {@link MessageEnvelope}, the per-channel
 * {@link MessageContents}, the {@link ChannelPlan}, the current canonical status with its full history
 * (ST-01) and the delivery attempts.
 *
 * <p>Every state change goes through the transition table of {@link MessageStatus} (ST-02), so an
 * illegal transition fails fast with {@link InvalidStatusTransitionException} instead of corrupting the
 * pipeline.
 */
public final class Message extends AggregateRoot<MessageId> {

    /** Stages at which the content may still be replaced by its rendered form (FR-4.3). */
    private static final Set<MessageStatus> CONTENT_MUTABLE_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(MessageStatus.ACCEPTED, MessageStatus.VALIDATED));

    private final MessageEnvelope envelope;
    private final Recipient recipient;
    private final ChannelPlan channelPlan;
    private final TemplateRef template;
    private final Timing timing;
    private final Instant acceptedAt;
    private final List<StatusChange> statusHistory = new ArrayList<>();
    private final List<DeliveryAttempt> attempts = new ArrayList<>();

    private MessageContents contents;
    private MessageStatus status;
    private RejectionReason statusReason;
    private Channel selectedChannel;
    private ProviderRef selectedProvider;
    private int segments;
    private Money cost;
    private Instant terminalAt;
    private MessageId duplicateOf;
    private boolean test;

    private Message(
            MessageEnvelope envelope,
            Recipient recipient,
            ChannelPlan channelPlan,
            MessageContents contents,
            TemplateRef template,
            Timing timing,
            Instant acceptedAt) {
        super(Guard.notNull(envelope, "Message.envelope").id());
        this.envelope = envelope;
        this.recipient = Guard.notNull(recipient, "Message.recipient");
        this.channelPlan = Guard.notNull(channelPlan, "Message.channelPlan");
        this.contents = Guard.notNull(contents, "Message.contents");
        this.template = template;
        this.timing = Guard.notNull(timing, "Message.timing");
        this.acceptedAt = Guard.notNull(acceptedAt, "Message.acceptedAt");
        requirePlanIsServedByContents(channelPlan, contents);
        this.status = MessageStatus.ACCEPTED;
        this.statusHistory.add(new StatusChange(
                MessageStatus.ACCEPTED,
                null,
                null,
                Actor.sourceSystem(envelope.streamId().value()),
                null,
                acceptedAt));
    }

    /** Accepts a submission from a source system (FR-1.1); the message starts in {@code ACCEPTED}. */
    public static Message accept(
            MessageEnvelope envelope,
            Recipient recipient,
            ChannelPlan channelPlan,
            MessageContents contents,
            TemplateRef template,
            Timing timing,
            Instant acceptedAt) {
        return new Message(envelope, recipient, channelPlan, contents, template, timing, acceptedAt);
    }

    /** Accepts a single-channel submission without a template and without timing constraints. */
    public static Message acceptSingleChannel(
            MessageEnvelope envelope, Recipient recipient, MessageContent content, Instant acceptedAt) {
        Guard.notNull(content, "content");
        return new Message(
                envelope,
                recipient,
                ChannelPlan.explicitChannel(content.channel()),
                MessageContents.of(content),
                null,
                Timing.immediate(),
                acceptedAt);
    }

    // ---------------------------------------------------------------------
    // Status machine (ST-01, ST-02)
    // ---------------------------------------------------------------------

    /** Applies a transition allowed by {@link MessageStatus} and appends a history entry. */
    public StatusChange transitionTo(MessageStatus next, Actor actor, Instant occurredAt) {
        return transitionTo(next, null, null, actor, occurredAt);
    }

    /** Applies a transition together with its canonical reason and provider detail (ST-01). */
    public StatusChange transitionTo(
            MessageStatus next, RejectionReason reason, String details, Actor actor, Instant occurredAt) {
        Guard.notNull(next, "next");
        if (!status.canTransitionTo(next)) {
            throw InvalidStatusTransitionException.of("Message", status, next);
        }
        ProviderCode providerCode = selectedProvider == null ? null : selectedProvider.code();
        StatusChange change = new StatusChange(next, reason, details, actor, providerCode, occurredAt);
        this.status = next;
        this.statusReason = reason;
        this.statusHistory.add(change);
        if (next.isTerminal()) {
            this.terminalAt = occurredAt;
        } else {
            this.terminalAt = null;
        }
        return change;
    }

    /** Validation passed (FR-1.4). */
    public StatusChange markValidated(Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.VALIDATED, actor, occurredAt);
    }

    /** Channel and provider are chosen (FR-2.3, MP-05). */
    public StatusChange markRouted(Channel channel, ProviderRef provider, Actor actor, Instant occurredAt) {
        assignRoute(channel, provider);
        return transitionTo(MessageStatus.ROUTED, actor, occurredAt);
    }

    public StatusChange markQueued(Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.QUEUED, actor, occurredAt);
    }

    public StatusChange markSending(Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.SENDING, actor, occurredAt);
    }

    /** The provider accepted the message; terminal status for push (PU-12). */
    public StatusChange markSentToProvider(String providerStatus, Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.SENT_TO_PROVIDER, null, providerStatus, actor, occurredAt);
    }

    /** Delivery confirmed by a provider DLR (§18.1, §18.2). */
    public StatusChange markDelivered(String providerStatus, Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.DELIVERED, null, providerStatus, actor, occurredAt);
    }

    /** The provider reported a non-delivery (§18.2 codes 2, 5, 6). */
    public StatusChange markUndelivered(String providerStatus, Actor actor, Instant occurredAt) {
        return transitionTo(
                MessageStatus.UNDELIVERED, RejectionReason.PROVIDER_REJECTED, providerStatus, actor, occurredAt);
    }

    /** A retry or a provider failover is scheduled (PR-01, FR-6.3). */
    public StatusChange markRetrying(String details, Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.RETRYING, null, details, actor, occurredAt);
    }

    /** All attempts and fallbacks are exhausted; the message goes to the DLQ (FR-3.3). */
    public StatusChange markFailed(String details, Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.FAILED, RejectionReason.ATTEMPTS_EXHAUSTED, details, actor, occurredAt);
    }

    /** Rejected by validation, suppression, quiet hours, quotas or PAN detection (FR-5.1…FR-5.4, SEC-05). */
    public StatusChange reject(RejectionReason reason, String details, Actor actor, Instant occurredAt) {
        Guard.notNull(reason, "reason");
        return transitionTo(MessageStatus.REJECTED, reason, details, actor, occurredAt);
    }

    /** Idempotency hit: the submission repeats an earlier message (FR-1.5). */
    public StatusChange markDuplicateOf(MessageId originalMessageId, Actor actor, Instant occurredAt) {
        this.duplicateOf = Guard.notNull(originalMessageId, "originalMessageId");
        return transitionTo(
                MessageStatus.DUPLICATE,
                RejectionReason.DUPLICATE_SUBMISSION,
                "duplicate of " + originalMessageId,
                actor,
                occurredAt);
    }

    /** Cancelled by a batch/stream stop or by the kill switch (FR-3.2). */
    public StatusChange cancel(RejectionReason reason, Actor actor, Instant occurredAt) {
        Guard.notNull(reason, "reason");
        return transitionTo(MessageStatus.CANCELLED, reason, null, actor, occurredAt);
    }

    /** TTL elapsed before delivery (FR-3.4). */
    public StatusChange expire(Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.EXPIRED, RejectionReason.TTL_EXPIRED, null, actor, occurredAt);
    }

    /** Manual DLQ retry: the lifecycle resumes with a new delivery attempt (FR-3.3, ST-02). */
    public StatusChange requeueFromDlq(Actor actor, Instant occurredAt) {
        return transitionTo(MessageStatus.QUEUED, null, "manual DLQ retry", actor, occurredAt);
    }

    // ---------------------------------------------------------------------
    // Pipeline data
    // ---------------------------------------------------------------------

    /** Assigns or replaces the route without changing the status — used by provider failover (FR-6.3). */
    public void assignRoute(Channel channel, ProviderRef provider) {
        Guard.notNull(channel, "channel");
        Guard.notNull(provider, "provider");
        Guard.isTrue(channelPlan.allows(channel), "channel %s is not allowed by the channel plan".formatted(channel));
        Guard.isTrue(contents.supports(channel), "message carries no content for channel " + channel);
        Guard.isTrue(
                provider.channel() == channel,
                "provider %s does not serve channel %s".formatted(provider.code(), channel));
        Guard.isTrue(!status.isTerminal(), "cannot re-route a message in terminal status " + status);
        this.selectedChannel = channel;
        this.selectedProvider = provider;
    }

    /** Replaces the content of a channel with its template-rendered form (FR-4.3). */
    public void applyRenderedContent(MessageContent rendered) {
        Guard.notNull(rendered, "rendered");
        Guard.isTrue(
                CONTENT_MUTABLE_STATUSES.contains(status), "content can no longer be rendered in status " + status);
        Guard.isTrue(
                contents.supports(rendered.channel()), "message carries no content for channel " + rendered.channel());
        this.contents = contents.with(rendered);
    }

    /** Stores the SMS segment count computed by {@code SegmentCalculator} (MP-06, §18.3). */
    public void applySegments(int segmentCount) {
        this.segments = Guard.positive(segmentCount, "segmentCount");
    }

    /** Stores the expected cost by provider tariff and segment count (FR-2.6, FR-6.2). */
    public void applyCost(Money messageCost) {
        this.cost = Guard.notNull(messageCost, "messageCost");
    }

    /** Marks the message as a configuration test: excluded from business statistics (FR-7.4). */
    public void markAsTest() {
        this.test = true;
    }

    /** Opens the next delivery attempt against the selected provider. */
    public DeliveryAttempt startAttempt(ProviderMessageId providerMessageId, Instant requestAt) {
        Guard.isTrue(selectedProvider != null, "message is not routed yet");
        DeliveryAttempt attempt = DeliveryAttempt.start(
                AttemptId.newId(), id(), selectedProvider, nextAttemptNumber(), providerMessageId, requestAt);
        attempts.add(attempt);
        return attempt;
    }

    /** Re-attaches an attempt loaded from storage or created elsewhere. */
    public void recordAttempt(DeliveryAttempt attempt) {
        Guard.notNull(attempt, "attempt");
        Guard.isTrue(attempt.messageId().equals(id()), "delivery attempt belongs to another message");
        Guard.isTrue(
                attempt.attemptNumber() == nextAttemptNumber(),
                "expected attempt number %d, got %d".formatted(nextAttemptNumber(), attempt.attemptNumber()));
        attempts.add(attempt);
    }

    public int nextAttemptNumber() {
        return attempts.size() + 1;
    }

    public Optional<DeliveryAttempt> latestAttempt() {
        return attempts.isEmpty() ? Optional.empty() : Optional.of(attempts.getLast());
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /** Whether the TTL or the send window has elapsed while the message is still in flight (FR-3.4). */
    public boolean isExpiredAt(Instant now) {
        return status.isInFlight() && timing.isExpiredAt(now, acceptedAt);
    }

    /** Whether the message can be delivered over the channel: plan, content and address all agree. */
    public boolean isDeliverableOn(Channel channel) {
        return channelPlan.allows(channel) && contents.supports(channel) && recipient.hasAddressFor(channel);
    }

    /** Channels the message could actually be delivered over, in channel-plan order where defined. */
    public List<Channel> deliverableChannels() {
        List<Channel> planned =
                channelPlan.channels().isEmpty() ? recipient.reachableChannels() : channelPlan.channels();
        return planned.stream().filter(this::isDeliverableOn).toList();
    }

    public MessageEnvelope envelope() {
        return envelope;
    }

    public Recipient recipient() {
        return recipient;
    }

    public ChannelPlan channelPlan() {
        return channelPlan;
    }

    public MessageContents contents() {
        return contents;
    }

    /** Content of the selected channel; empty until the message is routed. */
    public Optional<MessageContent> selectedContent() {
        return selectedChannel == null ? Optional.empty() : contents.forChannel(selectedChannel);
    }

    public Optional<TemplateRef> template() {
        return Optional.ofNullable(template);
    }

    public Timing timing() {
        return timing;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public MessageStatus status() {
        return status;
    }

    public Optional<RejectionReason> statusReason() {
        return Optional.ofNullable(statusReason);
    }

    public Optional<Channel> selectedChannel() {
        return Optional.ofNullable(selectedChannel);
    }

    public Optional<ProviderRef> selectedProvider() {
        return Optional.ofNullable(selectedProvider);
    }

    public int segments() {
        return segments;
    }

    public Optional<Money> cost() {
        return Optional.ofNullable(cost);
    }

    public Optional<Instant> terminalAt() {
        return Optional.ofNullable(terminalAt);
    }

    public Optional<MessageId> duplicateOf() {
        return Optional.ofNullable(duplicateOf);
    }

    public boolean isTest() {
        return test;
    }

    public List<StatusChange> statusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    public List<DeliveryAttempt> attempts() {
        return Collections.unmodifiableList(attempts);
    }

    private static void requirePlanIsServedByContents(ChannelPlan plan, MessageContents contents) {
        switch (plan.mode()) {
            case EXPLICIT, FALLBACK_CHAIN ->
                plan.channels()
                        .forEach(channel -> Guard.isTrue(
                                contents.supports(channel), "channel plan requires content for channel " + channel));
            default ->
                Guard.isTrue(
                        plan.channels().isEmpty() || plan.channels().stream().anyMatch(contents::supports),
                        "channel plan candidates carry no content: " + plan.channels());
        }
    }
}
