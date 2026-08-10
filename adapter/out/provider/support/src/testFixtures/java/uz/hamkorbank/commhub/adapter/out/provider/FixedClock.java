package uz.hamkorbank.commhub.adapter.out.provider;

import java.time.Instant;
import java.time.ZoneId;
import uz.hamkorbank.commhub.application.port.out.ClockPort;

/**
 * A clock the provider tests can assert against.
 *
 * <p>Every timestamp an adapter puts on a {@code ProviderAck} comes from {@code ClockPort}, so pinning
 * it is what lets a test say "the ack is stamped at the moment of the answer" instead of "the ack is
 * stamped at roughly now".
 */
public record FixedClock(Instant instant) implements ClockPort {

    public static final Instant DEFAULT = Instant.parse("2026-08-08T10:00:00Z");

    public static FixedClock at(Instant instant) {
        return new FixedClock(instant);
    }

    public static FixedClock standard() {
        return new FixedClock(DEFAULT);
    }

    @Override
    public Instant now() {
        return instant;
    }

    @Override
    public ZoneId zone() {
        return ZoneId.of("Asia/Tashkent");
    }
}
