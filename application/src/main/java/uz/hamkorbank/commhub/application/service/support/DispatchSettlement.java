package uz.hamkorbank.commhub.application.service.support;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.DispatchResult;
import uz.hamkorbank.commhub.application.dto.DispatchResult.DispatchOutcome;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.policy.SendingPolicy;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.service.pipeline.MessagePipeline;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Second transaction of a dispatch turn: the provider's answer applied to the message (ADR-0039).
 *
 * <p>The aggregate is loaded again rather than taken from the plan: between the two transactions the
 * message may have been touched by a provider callback, and the copy the preparation held is detached.
 * The open attempt is located on the fresh aggregate by the identity of the one that was started.
 *
 * <p>Every path ends with the claim released and the next attempt time written, which is what turns the
 * saga's "one more attempt is allowed" into an actual pause before it (PR-01).
 */
@Component
public class DispatchSettlement {

    private final ClockPort clock;
    private final MessageRepository messages;
    private final DlqRepository dlqEntries;
    private final MessagePipeline pipeline;
    private final MessageStatusNotifier notifier;
    private final SendingPolicy policy;
    private final BatchProgressRecorder progress;

    public DispatchSettlement(
            ClockPort clock,
            MessageRepository messages,
            DlqRepository dlqEntries,
            MessagePipeline pipeline,
            MessageStatusNotifier notifier,
            SendingPolicy policy,
            BatchProgressRecorder progress) {
        this.clock = Guard.notNull(clock, "clock");
        this.messages = Guard.notNull(messages, "messages");
        this.dlqEntries = Guard.notNull(dlqEntries, "dlqEntries");
        this.pipeline = Guard.notNull(pipeline, "pipeline");
        this.notifier = Guard.notNull(notifier, "notifier");
        this.policy = Guard.notNull(policy, "policy");
        this.progress = Guard.notNull(progress, "progress");
    }

    /** Applies what the provider answered and decides what happens to the message next. */
    @Transactional
    public DispatchResult settle(DispatchPlan plan, ProviderAck ack) {
        Guard.isTrue(plan != null && plan.isProceed(), "settle needs a proceeding plan");
        Guard.notNull(ack, "ack");
        Message message = messages.findById(plan.message().id())
                .orElseThrow(
                        () -> NotFoundException.of("message", plan.message().id()));
        DeliveryAttempt attempt = openAttempt(message, plan);
        ProviderRef provider = plan.provider();
        BatchProgressRecorder.Contribution before = progress.contributionOf(message);
        recordAttempt(attempt, ack);
        suppressIfAddressRejected(message, provider, ack);
        DispatchResult result = ack.isAccepted() ? sent(message, provider, ack) : handleFailure(message, provider, ack);
        messages.releaseClaim(message, nextAttemptAt(message, result));
        progress.apply(message, before);
        return result;
    }

    /**
     * The attempt the preparation opened, found on the freshly loaded aggregate.
     *
     * <p>Matched by identity and not by position: a callback arriving between the two transactions can
     * append history, and applying the provider's answer to the wrong attempt would misreport the very
     * thing this class exists to record.
     */
    private static DeliveryAttempt openAttempt(Message message, DispatchPlan plan) {
        return message.attempts().stream()
                .filter(candidate -> candidate.id().equals(plan.attempt().id()))
                .findFirst()
                .orElseThrow(() ->
                        NotFoundException.of("delivery attempt", plan.attempt().id()));
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
     * again.
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
        // The SLA of TC-01 is stated accept → provider, so it is measured here and not around the call:
        // what the customer waits for includes the queueing, the retries and any failover before this ack.
        notifier.recordStage(
                PipelineStages.ACCEPT_TO_PROVIDER,
                message.envelope().trafficClass(),
                Duration.between(message.acceptedAt(), ack.respondedAt()));
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

    /**
     * When the message becomes due again (PR-01).
     *
     * <p>A retry waits out the saga's backoff with jitter — without the pause the dispatcher would take
     * the message on its very next pass and burn the five-attempt budget in a second. Terminal outcomes
     * get no time at all: they no longer match the claim query anyway.
     */
    private Instant nextAttemptAt(Message message, DispatchResult result) {
        if (result.outcome() != DispatchOutcome.RETRY_SCHEDULED) {
            return null;
        }
        Duration backoff = policy.backoffFor(
                message.attempts().size(), ThreadLocalRandom.current().nextDouble());
        return clock.now().plus(backoff);
    }

    /** Re-routes the message onto a provider it has not tried yet (FR-6.3, PR-01). */
    private boolean reroute(Message message, Set<ProviderRef> excluded) {
        Optional<ProviderRef> next = pipeline.route(message, excluded).routed().map(routed -> routed.provider());
        next.ifPresent(provider -> message.assignRoute(provider.channel(), provider));
        return next.isPresent();
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
