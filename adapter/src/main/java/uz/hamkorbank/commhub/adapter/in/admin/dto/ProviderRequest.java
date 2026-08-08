package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.Map;

/**
 * Body of registering or updating a provider (§11.2 "Каналы и провайдеры", FR-2.1, FR-2.5).
 *
 * <p>{@code credentialsRef} is a reference into the secret store and never a credential: the Hub does
 * not accept secrets over its own API, and anything that looks like one here would end up in a request
 * log (SEC-04). {@code channel} and {@code adapterType} are read on registration only — a provider that
 * changed either would be a different provider with the same code and the same history.
 */
public record ProviderRequest(
        String channel,
        String adapterType,
        Integer weight,
        ProviderResponse.TariffDto tariff,
        RateLimitDto rateLimit,
        QuotaDto quota,
        String credentialsRef,
        Map<String, String> endpointConfig) {}
