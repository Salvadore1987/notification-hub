package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * State of the global kill switch after the operation (FR-3.2).
 *
 * <p>Already queued messages are not rewritten: the sending saga cancels them one by one while the
 * switch is active, and {@code CRITICAL_OTP} keeps flowing unless it was included explicitly.
 */
public record KillSwitchResult(boolean active, boolean includesCriticalOtp, Instant changedAt) {

    public static KillSwitchResult of(KillSwitchState state) {
        Guard.notNull(state, "state");
        return new KillSwitchResult(state.active(), state.includesCriticalOtp(), state.changedAt());
    }
}
