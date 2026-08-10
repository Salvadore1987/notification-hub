package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.KillSwitchResult;
import uz.hamkorbank.commhub.application.port.in.KillSwitch;
import uz.hamkorbank.commhub.application.port.in.command.KillSwitchCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The global stop of all sends (FR-3.2).
 *
 * <p>Flipping the switch does not touch a single message: the sending saga reads the state on every
 * turn and holds back everything it is allowed to hold back, so releasing the switch resumes the flow
 * exactly where it stopped. {@code CRITICAL_OTP} is only affected when the operator says so, which is
 * the difference between an emergency stop and silencing OTP delivery.
 */
@Service
public class KillSwitchService implements KillSwitch {

    private static final String ENTITY_TYPE = "kill-switch";
    private static final String ENTITY_ID = "global";

    private final ClockPort clock;
    private final KillSwitchPort killSwitch;
    private final AuditPort audit;

    public KillSwitchService(ClockPort clock, KillSwitchPort killSwitch, AuditPort audit) {
        this.clock = Guard.notNull(clock, "clock");
        this.killSwitch = Guard.notNull(killSwitch, "killSwitch");
        this.audit = Guard.notNull(audit, "audit");
    }

    @Override
    @Transactional
    public KillSwitchResult apply(KillSwitchCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        KillSwitchState before = killSwitch.state();
        KillSwitchState after = command.activate()
                ? KillSwitchState.activated(
                        command.includeCriticalOtp(), now, actorId(command.actor()), command.reason())
                : KillSwitchState.deactivated(now, actorId(command.actor()));
        killSwitch.update(after);
        audit.write(AuditEntry.changed(
                        command.actor(),
                        command.activate() ? "kill-switch.activate" : "kill-switch.deactivate",
                        ENTITY_TYPE,
                        ENTITY_ID,
                        AuditEntry.Change.of(describe(before), describe(after)),
                        now)
                .withReason(command.reason()));
        return KillSwitchResult.of(after);
    }

    @Override
    public KillSwitchResult state() {
        return KillSwitchResult.of(killSwitch.state());
    }

    private static String actorId(Actor actor) {
        return actor.id() == null ? actor.type().name() : actor.id();
    }

    private static String describe(KillSwitchState state) {
        return "active=%s, includesCriticalOtp=%s".formatted(state.active(), state.includesCriticalOtp());
    }
}
