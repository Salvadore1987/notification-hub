package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Global stop of all sends, or its release (FR-3.2).
 *
 * @param includeCriticalOtp stop {@code CRITICAL_OTP} as well; off by default, because a kill switch
 *     must not silence OTP unless that is asked for explicitly (FR-3.2)
 */
public record KillSwitchCommand(boolean activate, boolean includeCriticalOtp, Actor actor, String reason) {

    public KillSwitchCommand {
        Guard.notNull(actor, "KillSwitchCommand.actor");
        Guard.isTrue(!activate || reason != null, "activating the kill switch requires a reason (FR-7.3)");
    }

    /** Stops everything except OTP (FR-3.2). */
    public static KillSwitchCommand activate(Actor actor, String reason) {
        return new KillSwitchCommand(true, false, actor, reason);
    }

    public static KillSwitchCommand deactivate(Actor actor) {
        return new KillSwitchCommand(false, false, actor, null);
    }

    public Optional<String> reasonOptional() {
        return Optional.ofNullable(reason);
    }
}
