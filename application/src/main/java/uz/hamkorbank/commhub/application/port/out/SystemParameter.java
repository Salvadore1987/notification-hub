package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One operator-editable system parameter (§11.2 "Администрирование").
 *
 * <p>Deliberately a string keyed by a string, and deliberately small. What lives here is what an
 * operator changes at three in the morning and what must be the same on every instance within seconds:
 * banners, thresholds, switches. Everything with a shape — routing, providers, quotas, quiet hours — has
 * its own table and its own aggregate (AD-07), and moving any of it in here would replace a validated
 * configuration with free text.
 *
 * @param updatedBy login of whoever last wrote it; the audit journal holds the before and after
 */
public record SystemParameter(String key, String value, String description, Instant updatedAt, String updatedBy) {

    /** Bounded because the column is, and because a parameter this long is a document in disguise. */
    public static final int MAX_VALUE_LENGTH = 4_000;

    public SystemParameter {
        Guard.notBlank(key, "SystemParameter.key");
        Guard.maxLength(key, 128, "SystemParameter.key");
        Guard.maxLength(value, MAX_VALUE_LENGTH, "SystemParameter.value");
        Guard.maxLength(description, 512, "SystemParameter.description");
    }
}
