package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.time.Instant;

/** The moment the email tests pretend it is; the adapters take every instant from {@code ClockPort}. */
final class FixedInstants {

    static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");

    private FixedInstants() {}
}
