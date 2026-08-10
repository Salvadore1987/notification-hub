package uz.hamkorbank.commhub.application.service.support;

import uz.hamkorbank.commhub.application.dto.DispatchResult;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What the first transaction of a dispatch turn decided (ADR-0039).
 *
 * <p>Either the turn is over before any provider was called — the message was terminal, a guard held or
 * cancelled it, no route was left — and {@code terminal} carries the answer, already persisted and
 * published; or the turn proceeds, and the plan names the message, the provider and the attempt that
 * was opened for it.
 *
 * @param attempt the attempt opened and committed by the preparation; the settlement locates it again on
 *     the freshly loaded aggregate rather than trusting this detached copy
 */
public record DispatchPlan(Message message, ProviderRef provider, DeliveryAttempt attempt, DispatchResult terminal) {

    public DispatchPlan {
        Guard.isTrue(
                terminal != null || (message != null && provider != null && attempt != null),
                "a proceeding DispatchPlan needs a message, a provider and an open attempt");
    }

    /** The turn ended in the first transaction; nothing is to be sent. */
    public static DispatchPlan done(DispatchResult terminal) {
        return new DispatchPlan(null, null, null, Guard.notNull(terminal, "terminal"));
    }

    /** The message is ready to be handed to the provider. */
    public static DispatchPlan proceed(Message message, ProviderRef provider, DeliveryAttempt attempt) {
        return new DispatchPlan(message, provider, attempt, null);
    }

    public boolean isProceed() {
        return terminal == null;
    }
}
