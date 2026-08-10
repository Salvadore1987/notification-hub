package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import uz.hamkorbank.commhub.domain.model.Timing;

/**
 * {@link Timing} inside a {@code timing} column (FR-1.4, FR-8.5).
 *
 * <p>The TTL is stored as whole seconds: the schedule the source system asks for never has sub-second
 * resolution, and seconds read the same way in the admin panel and in a provider request.
 */
public record TimingJson(
        String sendAfter,
        String sendBefore,
        Long ttlSeconds,
        boolean localTime,
        boolean sendEvenly,
        String allowedStartTime,
        String allowedEndTime) {

    public static TimingJson of(Timing timing) {
        if (timing == null) {
            return null;
        }
        return new TimingJson(
                timing.sendAfter() == null ? null : timing.sendAfter().toString(),
                timing.sendBefore() == null ? null : timing.sendBefore().toString(),
                timing.ttl() == null ? null : timing.ttl().toSeconds(),
                timing.localTime(),
                timing.sendEvenly(),
                timing.allowedStartTime() == null
                        ? null
                        : timing.allowedStartTime().toString(),
                timing.allowedEndTime() == null ? null : timing.allowedEndTime().toString());
    }

    public Timing toDomain() {
        return new Timing(
                sendAfter == null ? null : Instant.parse(sendAfter),
                sendBefore == null ? null : Instant.parse(sendBefore),
                ttlSeconds == null ? null : Duration.ofSeconds(ttlSeconds),
                localTime,
                sendEvenly,
                allowedStartTime == null ? null : LocalTime.parse(allowedStartTime),
                allowedEndTime == null ? null : LocalTime.parse(allowedEndTime));
    }

    public static Timing toDomain(TimingJson json) {
        return json == null ? Timing.immediate() : json.toDomain();
    }
}
