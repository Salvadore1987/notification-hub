package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.KillSwitchResult;
import uz.hamkorbank.commhub.application.port.in.command.KillSwitchCommand;

/**
 * Global stop of all sends of the Hub (FR-3.2, §11.2 "Администрирование").
 *
 * <p>{@code CRITICAL_OTP} keeps flowing unless the operator asks for it explicitly. Both flipping the
 * switch and reading it are audited (FR-7.3, SEC-03).
 */
public interface KillSwitch {

    KillSwitchResult apply(KillSwitchCommand command);

    /** Current state, used by the pipeline and by the admin dashboard. */
    KillSwitchResult state();
}
