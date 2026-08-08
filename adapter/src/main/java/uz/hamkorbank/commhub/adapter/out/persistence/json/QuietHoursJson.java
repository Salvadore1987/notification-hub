package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.time.LocalTime;
import java.time.ZoneId;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.type.QuietHoursBehavior;

/**
 * {@link QuietHours} inside a {@code jsonb} column (FR-5.3).
 *
 * <p>Times and zone are ISO strings rather than Jackson's temporal encoding: the column is read by
 * reports and by the admin panel, and {@code "22:00"} survives a Jackson upgrade unchanged.
 */
public record QuietHoursJson(String start, String end, String zone, String behavior) {

    public static QuietHoursJson of(QuietHours quietHours) {
        if (quietHours == null) {
            return null;
        }
        return new QuietHoursJson(
                quietHours.start().toString(),
                quietHours.end().toString(),
                quietHours.zone().getId(),
                quietHours.behavior().name());
    }

    public QuietHours toDomain() {
        return new QuietHours(
                LocalTime.parse(start), LocalTime.parse(end), ZoneId.of(zone), QuietHoursBehavior.valueOf(behavior));
    }

    public static QuietHours toDomain(QuietHoursJson json) {
        return json == null ? null : json.toDomain();
    }
}
