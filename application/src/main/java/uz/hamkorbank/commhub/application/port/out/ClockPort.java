package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The only source of "now" for the application layer.
 *
 * <p>The domain never reads a clock — aggregates take instants as parameters — so every timestamp of
 * the pipeline (acceptance, status changes, TTL, quiet hours, dedup window) originates here, which is
 * also what makes the use cases deterministically testable (QA-01).
 */
public interface ClockPort {

    /** Current instant in UTC. */
    Instant now();

    /** Business time zone used for quiet hours and reporting, {@code Asia/Tashkent} (FR-5.3, UI-04). */
    ZoneId zone();
}
