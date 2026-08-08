package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * State of the global stop of all sends (FR-3.2).
 *
 * <p>{@code CRITICAL_OTP} keeps flowing unless the operator explicitly asks for it to be stopped too
 * ({@link #includesCriticalOtp()}), which mirrors {@link TrafficClass#stoppableByDefault()}.
 *
 * @param changedBy user login that flipped the switch; {@code null} while it was never used
 */
public record KillSwitchState(
        boolean active, boolean includesCriticalOtp, Instant changedAt, String changedBy, String reason) {

    private static final KillSwitchState INACTIVE = new KillSwitchState(false, false, null, null, null);

    public KillSwitchState {
        Guard.isTrue(!active || changedAt != null, "an active kill switch must carry changedAt");
    }

    /** Nothing is stopped — the state of a healthy system. */
    public static KillSwitchState inactive() {
        return INACTIVE;
    }

    public static KillSwitchState activated(boolean includesCriticalOtp, Instant changedAt, String by, String reason) {
        return new KillSwitchState(true, includesCriticalOtp, changedAt, by, reason);
    }

    public static KillSwitchState deactivated(Instant changedAt, String by) {
        return new KillSwitchState(false, false, changedAt, by, null);
    }

    /** Whether messages of this traffic class must be held back (FR-3.2). */
    public boolean stops(TrafficClass trafficClass) {
        Guard.notNull(trafficClass, "trafficClass");
        return active && (includesCriticalOtp || trafficClass.stoppableByDefault());
    }
}
