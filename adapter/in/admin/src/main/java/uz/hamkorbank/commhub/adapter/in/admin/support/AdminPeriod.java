package uz.hamkorbank.commhub.adapter.in.admin.support;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The {@code from}/{@code to} pair every list and report of the admin panel carries (UI-03, UI-04).
 *
 * <p>One place resolves it because the default is load-bearing rather than cosmetic. {@code message} is
 * partitioned by {@code accepted_at} (DB-02), so a screen opened without a period would be a scan of
 * every partition retention has kept — and the screens that get opened without one are the ones opened
 * in a hurry. The default is the last 24 hours, which is what "today" means on a dashboard somebody is
 * looking at during an incident.
 *
 * <p>Instants are UTC in and UTC out. UI-04 puts the Asia/Tashkent rendering in the browser, where the
 * person and their timezone actually are; a backend that converted would have to guess whose day it is.
 */
@Component
public class AdminPeriod {

    /** What a screen shows when nobody asked for a period. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /**
     * The widest period a single request may ask for.
     *
     * <p>Not a policy about how much history exists — the retention rules decide that — but a bound on
     * one query. A year-long report is a job, not an HTTP request, and the way that fact is usually
     * discovered is a connection pool held by three of them.
     */
    public static final Duration MAX_WINDOW = Duration.ofDays(92);

    private final ClockPort clock;

    public AdminPeriod(ClockPort clock) {
        this.clock = Guard.notNull(clock, "clock");
    }

    /** Resolves the pair, filling in the defaults and refusing a period no query should run. */
    public Period resolve(String from, String to) {
        Instant end = parse(to, "to", clock.now());
        Instant start = parse(from, "from", end.minus(DEFAULT_WINDOW));
        if (end.isBefore(start)) {
            throw InboundContractException.invalid("to", "precedes from");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw InboundContractException.invalid(
                    "from", "period exceeds the maximum of %d days".formatted(MAX_WINDOW.toDays()));
        }
        return new Period(start, end);
    }

    private static Instant parse(String value, String field, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw InboundContractException.invalid(field, "is not an ISO-8601 instant", e);
        }
    }

    /** A resolved period; {@code to} is exclusive, as every query in this system reads it. */
    public record Period(Instant from, Instant to) {}
}
