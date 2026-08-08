package uz.hamkorbank.commhub.adapter.out.persistence.json;

import uz.hamkorbank.commhub.domain.model.RateLimit;

/** {@link RateLimit} inside the {@code provider.rate_limit_config} column (FR-2.5, §18.2). */
public record RateLimitJson(int tps, int perMinute, int perRecipientPerHour) {

    public static RateLimitJson of(RateLimit rateLimit) {
        if (rateLimit == null || rateLimit.isUnlimited()) {
            return null;
        }
        return new RateLimitJson(rateLimit.tps(), rateLimit.perMinute(), rateLimit.perRecipientPerHour());
    }

    public RateLimit toDomain() {
        return new RateLimit(tps, perMinute, perRecipientPerHour);
    }

    public static RateLimit toDomain(RateLimitJson json) {
        return json == null ? RateLimit.unlimited() : json.toDomain();
    }
}
