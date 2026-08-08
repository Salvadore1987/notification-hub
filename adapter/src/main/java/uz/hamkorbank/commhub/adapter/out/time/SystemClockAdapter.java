package uz.hamkorbank.commhub.adapter.out.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ClockPort;

/**
 * The system clock behind {@link ClockPort}.
 *
 * <p>Instants are read in UTC — the storage timezone of the whole system — while the business zone
 * stays {@code Asia/Tashkent}, which is what quiet hours and daily quotas are cut by (FR-5.3, UI-04).
 * A single adapter keeps that distinction in one place instead of scattering {@code ZoneId.of} calls
 * through the use cases.
 */
@Component
public class SystemClockAdapter implements ClockPort {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Tashkent");

    private final Clock clock;

    public SystemClockAdapter() {
        this(Clock.systemUTC());
    }

    SystemClockAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public ZoneId zone() {
        return BUSINESS_ZONE;
    }
}
