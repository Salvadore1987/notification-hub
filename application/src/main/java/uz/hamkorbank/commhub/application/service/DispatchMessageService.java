package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.DispatchResult;
import uz.hamkorbank.commhub.application.dto.DispatchResult.DispatchOutcome;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.policy.SendingPolicy;
import uz.hamkorbank.commhub.application.port.in.DispatchMessage;
import uz.hamkorbank.commhub.application.port.in.command.DispatchMessageCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.service.pipeline.MessagePipeline;
import uz.hamkorbank.commhub.application.service.support.DispatchGate;
import uz.hamkorbank.commhub.application.service.support.DispatchGuards;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.application.service.support.ProviderGateway;
import uz.hamkorbank.commhub.application.service.support.RoutingOutcome;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The sending saga: one provider attempt per call, plus the decision what happens next (AD-04,
 * PR-01, FR-2.2, FR-6.3, FR-3.3).
 *
 * <p>Order of a turn: guards (TTL, batch, stream, kill switch, send window) → provider call → attempt
 * recorded → next step. A retryable failure keeps the message alive on the same provider until its
 * budget is used up, then fails over to the next provider of the channel; a provider-wide failure
 * (Playmobile 102, §18.1) fails over immediately; a permanent rejection ends the message as
 * {@code UNDELIVERED}; an exhausted budget ends it as {@code FAILED} with a DLQ entry.
 *
 * <p>Runs on virtual threads, one dispatcher per traffic class, which is what keeps a bulk batch from
 * eating into the OTP SLA (AR-07, TC-01).
 */
@Service
public class DispatchMessageService implements DispatchMessage {

    private final ClockPort clock;
    private final MessageRepository messages;
    private final DlqRepository dlqEntries;
    private final ProviderGateway gateway;
    private final DispatchGuards guards;
    private final MessagePipeline pipeline;
    private final MessageStatusNotifier notifier;
    private final SendingPolicy policy;

    public DispatchMessageService(
            ClockPort clock,
            MessageRepository messages,
            DlqRepository dlqEntries,
            ProviderGateway gateway,
            DispatchGuards guards,
            MessagePipeline pipeline,
            MessageStatusNotifier notifier,
            SendingPolicy policy) {
        this.clock = Guard.notNull(clock, "clock");
        this.messages = Guard.notNull(messages, "messages");
        this.dlqEntries = Guard.notNull(dlqEntries, "dlqEntries");
        this.gateway = Guard.notNull(gateway, "gateway");
        this.guards = Guard.notNull(guards, "guards");
        this.pipeline = Guard.notNull(pipeline, "pipeline");
        this.notifier = Guard.notNull(notifier, "notifier");
        this.policy = Guard.notNull(policy, "policy");
    }

    @Override
    @Transactional
    public DispatchResult dispatch(DispatchMessageCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        Message message = messages.findById(command.messageId())
                .orElseThrow(() -> NotFoundException.of("message", command.messageId()));
        if (message.status().isTerminal()) {
            return DispatchResult.skipped(message.id(), message.status(), DispatchOutcome.SKIPPED);
        }
        DispatchGate gate = guards.evaluate(message, now);
        if (!gate.isProceed()) {
            return applyGate(message, gate, now);
        }
        if (message.selectedProvider().isEmpty() && !reroute(message, Set.of())) {
            return noRoute(message, now);
        }
        return submit(message, message.selectedProvider().orElseThrow(), now);
    }

    /** Hands the message to the provider and records the attempt (PR-03). */
    private DispatchResult submit(Message message, ProviderRef provider, Instant now) {
        moveToSending(message, now);
        ProviderMessageId providerMessageId = gateway.providerMessageIdFor(message);
        DeliveryAttempt attempt = message.startAttempt(providerMessageId, now);
        ProviderAck ack = gateway.submit(message, provider, attempt, null);
        recordAttempt(attempt, ack);
        suppressIfAddressRejected(message, provider, ack);
        if (ack.isAccepted()) {
            return sent(message, provider, ack);
        }
        return handleFailure(message, provider, ack);
    }

    /** Applies the answer of the provider to the open attempt (§18.1, §18.2). */
    private static void recordAttempt(DeliveryAttempt attempt, ProviderAck ack) {
        switch (ack.result()) {
            case ACCEPTED -> attempt.succeed(ack.responseCode(), ack.providerMessageId(), ack.respondedAt());
            case REJECTED -> attempt.reject(ack.responseCode(), ack.errorDescription(), ack.respondedAt());
            case TIMEOUT -> attempt.timeout(ack.respondedAt());
            default -> attempt.fail(ack.responseCode(), ack.errorClass(), ack.errorDescription(), ack.respondedAt());
        }
    }

    /**
     * The provider declared the address itself unusable — blacklisted number (§18.2 code 20), unregistered
     * push token (PU-04, PU-08) — so it goes on the suppression list (FR-5.1).
     *
     * <p>Recorded before the message's own outcome is decided, and deliberately not conditional on it: the
     * statement is about the address, not about this message, and the next batch would otherwise send to it
     * again. Failing over to another provider is still allowed for this message — a blacklist is usually
     * one provider's, and the ack's error class decides that on its own.
     */
    private void suppressIfAddressRejected(Message message, ProviderRef provider, ProviderAck ack) {
        if (ack.invalidRecipient()) {
            pipeline.suppress(
                    message,
                    provider.channel(),
                    SuppressionReason.PROVIDER_BLACKLIST,
                    Actor.provider(provider.code().value()),
                    ack.respondedAt());
        }
    }

    private DispatchResult sent(Message message, ProviderRef provider, ProviderAck ack) {
        StatusChange change = message.markSentToProvider(
                ack.providerStatus(), Actor.provider(provider.code().value()), ack.respondedAt());
        return complete(message, change, DispatchOutcome.SENT, provider);
    }

    /**
     * Decides between retry, failover, permanent non-delivery and the DLQ (PR-01, FR-2.2, FR-3.3).
     *
     * <p>A non-retryable rejection is a property of the message, not of the provider — content or
     * parameters the next provider would refuse just as well — so it ends the message right away
     * (§18.1 codes 401–406).
     */
    private DispatchResult handleFailure(Message message, ProviderRef provider, ProviderAck ack) {
        Instant now = ack.respondedAt();
        if (!ack.isRetryable() && !ack.isBlocking()) {
            StatusChange change = message.markUndelivered(
                    ack.errorDescription(), Actor.provider(provider.code().value()), now);
            return complete(message, change, DispatchOutcome.UNDELIVERED, provider);
        }
        boolean sameProviderAllowed =
                !ack.isBlocking() && policy.allowsRetryOnSameProvider(attemptsOn(message, provider));
        boolean failedOver = !sameProviderAllowed && reroute(message, triedProviders(message));
        if (policy.allowsAttempt(message.attempts().size()) && (sameProviderAllowed || failedOver)) {
            StatusChange change = message.markRetrying(retryDetail(ack, failedOver), Actor.system(), now);
            return complete(
                    message,
                    change,
                    DispatchOutcome.RETRY_SCHEDULED,
                    message.selectedProvider().orElse(null));
        }
        return fail(message, ack, provider, now);
    }

    /** All attempts and fallbacks are exhausted: the message goes to the DLQ (FR-3.3). */
    private DispatchResult fail(Message message, ProviderAck ack, ProviderRef provider, Instant now) {
        StatusChange change = message.markFailed(ack.errorDescription(), Actor.system(), now);
        dlqEntries.save(DlqEntry.of(message.id(), RejectionReason.ATTEMPTS_EXHAUSTED, ack.errorDescription(), now));
        messages.save(message);
        notifier.publishDlq(message, change);
        return DispatchResult.of(
                message.id(),
                message.status(),
                DispatchOutcome.FAILED,
                provider,
                message.attempts().size());
    }

    /** Applies a guard that stopped the message before any provider call (FR-3.2, FR-3.4). */
    private DispatchResult applyGate(Message message, DispatchGate gate, Instant now) {
        return switch (gate.decision()) {
            case DEFER -> DispatchResult.skipped(message.id(), message.status(), DispatchOutcome.DEFERRED);
            case EXPIRE -> {
                StatusChange change = message.expire(Actor.system(), now);
                yield complete(message, change, DispatchOutcome.EXPIRED, null);
            }
            case CANCEL -> {
                StatusChange change = message.cancel(gate.reason(), Actor.system(), now);
                yield complete(message, change, DispatchOutcome.CANCELLED, null);
            }
            default -> DispatchResult.skipped(message.id(), message.status(), DispatchOutcome.SKIPPED);
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

    /** Re-routes the message onto a provider it has not tried yet (FR-6.3, PR-01). */
    private boolean reroute(Message message, Set<ProviderRef> excluded) {
        RoutingOutcome outcome = pipeline.route(message, excluded);
        Optional<ProviderRef> next = outcome.routed().map(routed -> routed.provider());
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

    private static Set<ProviderRef> triedProviders(Message message) {
        Set<ProviderRef> tried = new LinkedHashSet<>();
        message.attempts().forEach(attempt -> tried.add(attempt.provider()));
        return tried;
    }

    private static int attemptsOn(Message message, ProviderRef provider) {
        return (int) message.attempts().stream()
                .filter(attempt -> attempt.provider().equals(provider))
                .count();
    }

    private static String retryDetail(ProviderAck ack, boolean failedOver) {
        String cause = ack.result() == AttemptResult.TIMEOUT ? "provider timed out" : ack.errorDescription();
        return failedOver ? "failover after: " + cause : "retry after: " + cause;
    }
}
