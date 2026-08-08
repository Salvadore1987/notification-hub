package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.DispatchResult;
import uz.hamkorbank.commhub.application.port.in.command.DispatchMessageCommand;

/**
 * The sending saga: hands one routed message to its provider and reacts to the answer (AD-04, PR-01).
 *
 * <p>One call performs at most one provider attempt. Failures are turned into a retry on the same
 * provider, a failover to the next one (FR-2.2, FR-6.3) or the DLQ once everything is exhausted
 * (FR-3.3); the caller re-enqueues the message while {@link DispatchResult#needsAnotherTurn()} holds.
 *
 * <p>Callers run on virtual threads, one dispatcher per traffic class, which is what keeps bulk load
 * away from the OTP SLA (AR-07, TC-01).
 */
public interface DispatchMessage {

    DispatchResult dispatch(DispatchMessageCommand command);
}
