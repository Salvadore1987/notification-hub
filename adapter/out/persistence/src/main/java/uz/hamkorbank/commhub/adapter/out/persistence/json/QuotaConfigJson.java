package uz.hamkorbank.commhub.adapter.out.persistence.json;

import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;

/** {@link QuotaConfig} inside a {@code jsonb} column (FR-2.6); a {@code null} limit is unlimited. */
public record QuotaConfigJson(
        Long dailyCount, Long monthlyCount, MoneyJson dailyCost, MoneyJson monthlyCost, String behavior) {

    public static QuotaConfigJson of(QuotaConfig quota) {
        if (quota == null || quota.isUnlimited()) {
            return null;
        }
        return new QuotaConfigJson(
                quota.dailyCount(),
                quota.monthlyCount(),
                MoneyJson.of(quota.dailyCost()),
                MoneyJson.of(quota.monthlyCost()),
                quota.behavior().name());
    }

    public QuotaConfig toDomain() {
        return new QuotaConfig(
                dailyCount,
                monthlyCount,
                MoneyJson.toDomain(dailyCost),
                MoneyJson.toDomain(monthlyCost),
                QuotaExhaustionBehavior.valueOf(behavior));
    }

    public static QuotaConfig toDomain(QuotaConfigJson json) {
        return json == null ? QuotaConfig.unlimited() : json.toDomain();
    }
}
