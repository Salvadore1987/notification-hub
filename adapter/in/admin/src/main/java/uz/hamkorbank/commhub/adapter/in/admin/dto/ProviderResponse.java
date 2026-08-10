package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.Map;

/**
 * A provider profile as the administration screen shows it (§11.2 "Каналы и провайдеры", FR-2.1).
 *
 * <p>The credentials are a <em>reference</em> and never the value behind it: the panel shows operators
 * where a provider's credentials come from, so a wrong reference is visible, and the secret itself never
 * leaves the secret store (SEC-04).
 */
public record ProviderResponse(
        String providerId,
        String code,
        String channel,
        String adapterType,
        int weight,
        TariffDto tariff,
        RateLimitDto rateLimit,
        StateResponse state) {

    /** What a message costs over this provider (FR-6.2); one of the two is always set. */
    public record TariffDto(String perMessage, String perSegment) {}

    /**
     * Everything that changes at runtime (FR-2.7, FR-6.3).
     *
     * @param selectable the single flag that answers "why is nothing going out over it?"
     * @param endpointConfig transport settings interpreted by the adapter; never credentials
     */
    public record StateResponse(
            boolean enabled,
            boolean maintenance,
            String health,
            boolean selectable,
            QuotaDto quota,
            String credentialsRef,
            Map<String, String> endpointConfig) {}
}
