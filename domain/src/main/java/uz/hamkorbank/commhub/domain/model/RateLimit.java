package uz.hamkorbank.commhub.domain.model;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Throughput limits of a provider (FR-2.5).
 *
 * <p>{@code 0} means "no limit". {@link #perRecipientPerHour()} models anti-spam rules of the
 * providers, e.g. the SMS Gate limit of 50 SMS per hour per number (§18.2).
 */
public record RateLimit(int tps, int perMinute, int perRecipientPerHour) {

    public RateLimit {
        Guard.notNegative(tps, "RateLimit.tps");
        Guard.notNegative(perMinute, "RateLimit.perMinute");
        Guard.notNegative(perRecipientPerHour, "RateLimit.perRecipientPerHour");
    }

    public static RateLimit unlimited() {
        return new RateLimit(0, 0, 0);
    }

    public static RateLimit ofTps(int tps) {
        return new RateLimit(tps, 0, 0);
    }

    public boolean hasTpsLimit() {
        return tps > 0;
    }

    public boolean hasPerMinuteLimit() {
        return perMinute > 0;
    }

    public boolean hasPerRecipientLimit() {
        return perRecipientPerHour > 0;
    }

    public boolean isUnlimited() {
        return !hasTpsLimit() && !hasPerMinuteLimit() && !hasPerRecipientLimit();
    }
}
