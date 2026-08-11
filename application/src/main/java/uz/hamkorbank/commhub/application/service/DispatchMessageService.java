package uz.hamkorbank.commhub.application.service;

import org.springframework.stereotype.Service;
import uz.hamkorbank.commhub.application.dto.DispatchResult;
import uz.hamkorbank.commhub.application.port.in.DispatchMessage;
import uz.hamkorbank.commhub.application.port.in.command.DispatchMessageCommand;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.service.support.DispatchPlan;
import uz.hamkorbank.commhub.application.service.support.DispatchPreparation;
import uz.hamkorbank.commhub.application.service.support.DispatchSettlement;
import uz.hamkorbank.commhub.application.service.support.ProviderGateway;
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
 * <p><strong>The turn spans two transactions and the provider call sits between them</strong>
 * (ADR-0039). {@link DispatchPreparation} commits the guards, the route and the open attempt;
 * {@link DispatchSettlement} applies the answer to a freshly loaded aggregate. This class holds no
 * transaction of its own — that is the whole point, and marking it {@code @Transactional} again would
 * put an HTTP call back inside a database transaction, where a killed pod turns one message into two
 * SMS and sixty-four parallel sends exhaust a pool of twenty connections.
 *
 * <p>Runs on virtual threads, one dispatcher per traffic class, which is what keeps a bulk batch from
 * eating into the OTP SLA (AR-07, TC-01).
 */
@Service
public class DispatchMessageService implements DispatchMessage {

    private final DispatchPreparation preparation;
    private final ProviderGateway gateway;
    private final DispatchSettlement settlement;

    public DispatchMessageService(
            DispatchPreparation preparation, ProviderGateway gateway, DispatchSettlement settlement) {
        this.preparation = Guard.notNull(preparation, "preparation");
        this.gateway = Guard.notNull(gateway, "gateway");
        this.settlement = Guard.notNull(settlement, "settlement");
    }

    @Override
    public DispatchResult dispatch(DispatchMessageCommand command) {
        Guard.notNull(command, "command");
        DispatchPlan plan = preparation.prepare(command.messageId());
        if (!plan.isProceed()) {
            return plan.terminal();
        }
        ProviderAck ack = gateway.submit(plan.message(), plan.provider(), plan.attempt(), null);
        return settlement.settle(plan, ack);
    }
}
