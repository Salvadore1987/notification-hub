package uz.hamkorbank.commhub.application.service.support;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.DispatchResult;
import uz.hamkorbank.commhub.application.dto.DispatchResult.DispatchOutcome;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.policy.SendingPolicy;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.service.pipeline.MessagePipeline;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * First transaction of a dispatch turn: everything that happens before the provider is called (ADR-0039).
 *
 * <p>Guards, routing and the opening of the delivery attempt are committed <em>before</em> the call, so
 * that the call itself holds no database connection and no transaction. That is what lets a dispatcher
 * run sixty-four sends in parallel against a pool of twenty, and it is what stops a pod killed after the
 * provider accepted from rolling the attempt back — a rollback there means the message is claimed again
 * and the customer gets a second SMS.
 *
 * <p>A separate bean rather than a second method of the saga because Spring proxies do not intercept
 * self-invocation: two transaction boundaries need two beans.
 */
@Component
public class DispatchPreparation {

    private final ClockPort clock;
    private final MessageRepository messages;
    private final DispatchGuards guards;
    private final MessagePipeline pipeline;
    private final MessageStatusNotifier notifier;
    private final ProviderGateway gateway;
    private final SendingPolicy policy;
    private final BatchProgressRecorder progress;

    public DispatchPreparation(
            ClockPort clock,
            MessageRepository messages,
            DispatchGuards guards,
            MessagePipeline pipeline,
            MessageStatusNotifier notifier,
            ProviderGateway gateway,
            SendingPolicy policy,
            BatchProgressRecorder progress) {
        this.clock = Guard.notNull(clock, "clock");
        this.messages = Guard.notNull(messages, "messages");
        this.guards = Guard.notNull(guards, "guards");
        this.pipeline = Guard.notNull(pipeline, "pipeline");
        this.notifier = Guard.notNull(notifier, "notifier");
        this.gateway = Guard.notNull(gateway, "gateway");
        this.policy = Guard.notNull(policy, "policy");
        this.progress = Guard.notNull(progress, "progress");
    }

    /** Decides whether this turn calls a provider, and opens the attempt if it does. */
    @Transactional
    public DispatchPlan prepare(MessageId messageId) {
        Guard.notNull(messageId, "messageId");
        Instant now = clock.now();
        Message message = messages.findById(messageId).orElseThrow(() -> NotFoundException.of("message", messageId));
        // Вклад снимается до изменений: дельта — разность вкладов, а не приращение (ADR-0040).
        BatchProgressRecorder.Contribution before = progress.contributionOf(message);
        if (message.status().isTerminal()) {
            return release(
                    message,
                    null,
                    DispatchResult.skipped(message.id(), message.status(), DispatchOutcome.SKIPPED),
                    before);
        }
        DispatchGate gate = guards.evaluate(message, now);
        if (!gate.isProceed()) {
            return applyGate(message, gate, now, before);
        }
        if (message.selectedProvider().isEmpty() && !reroute(message)) {
            return release(message, null, noRoute(message, now), before);
        }
        ProviderRef provider = message.selectedProvider().orElseThrow();
        moveToSending(message, now);
        ProviderMessageId providerMessageId = gateway.providerMessageIdFor(message);
        DeliveryAttempt attempt = message.startAttempt(providerMessageId, now);
        messages.save(message);
        return DispatchPlan.proceed(message, provider, attempt);
    }

    /** Applies a guard that stopped the message before any provider call (FR-3.2, FR-3.4). */
    private DispatchPlan applyGate(
            Message message, DispatchGate gate, Instant now, BatchProgressRecorder.Contribution before) {
        return switch (gate.decision()) {
            case DEFER ->
                release(
                        message,
                        gate.notBeforeOptional().orElseGet(() -> now.plus(policy.deferBackoff())),
                        DispatchResult.skipped(message.id(), message.status(), DispatchOutcome.DEFERRED),
                        before);
            case EXPIRE -> {
                StatusChange change = message.expire(Actor.system(), now);
                yield release(message, null, complete(message, change, DispatchOutcome.EXPIRED, null), before);
            }
            case CANCEL -> {
                StatusChange change = message.cancel(gate.reason(), Actor.system(), now);
                yield release(message, null, complete(message, change, DispatchOutcome.CANCELLED, null), before);
            }
            default ->
                release(
                        message,
                        null,
                        DispatchResult.skipped(message.id(), message.status(), DispatchOutcome.SKIPPED),
                        before);
        };
    }

    /** No provider of the channel can take the message any more (FR-2.2, FR-6.3). */
    private DispatchResult noRoute(Message message, Instant now) {
        StatusChange change = message.reject(
                RejectionReason.NO_ROUTE_AVAILABLE,
                "no selectable provider is left for the message",
                Actor.system(),
                now);
        return complete(message, change, DispatchOutcome.NO_ROUTE, null);
    }

    /** Re-routes a message that has no provider yet (FR-2.2). */
    private boolean reroute(Message message) {
        Optional<ProviderRef> next = pipeline.route(message, Set.of()).routed().map(routed -> routed.provider());
        next.ifPresent(provider -> message.assignRoute(provider.channel(), provider));
        return next.isPresent();
    }

    /** {@code ROUTED} and {@code RETRYING} both lead into {@code SENDING} (§6.3). */
    private static void moveToSending(Message message, Instant now) {
        if (message.status() == MessageStatus.ROUTED) {
            message.markQueued(Actor.system(), now);
        }
        message.markSending(Actor.system(), now);
    }

    private DispatchResult complete(
            Message message, StatusChange change, DispatchOutcome outcome, ProviderRef provider) {
        messages.save(message);
        notifier.publish(message, change);
        return DispatchResult.of(
                message.id(),
                message.status(),
                outcome,
                provider,
                message.attempts().size());
    }

    /**
     * Gives the claim back and settles the batch counters, since this turn is over (FR-3.1).
     *
     * <p>Both happen here and not in the caller because every path that ends the turn goes through this
     * method: a counter updated on some of them would be worse than one updated on none.
     */
    private DispatchPlan release(
            Message message, Instant nextAttemptAt, DispatchResult result, BatchProgressRecorder.Contribution before) {
        messages.releaseClaim(message, nextAttemptAt);
        progress.apply(message, before);
        return DispatchPlan.done(result);
    }
}
