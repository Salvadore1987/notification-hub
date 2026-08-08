package uz.hamkorbank.commhub.application.dto;

import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A provider profile as the administration screens see it (FR-2.1, FR-2.5, FR-2.7, PR-02).
 *
 * <p>{@link State#credentialsRef()} is the reference, never the secret behind it: the panel shows
 * operators <em>where</em> the credentials come from so a wrong reference is visible, and the value
 * itself never leaves the secret store (SEC-04, SG-04).
 */
public record ProviderView(
        ProviderId providerId,
        ProviderCode code,
        Channel channel,
        AdapterType adapterType,
        int weight,
        Tariff tariff,
        RateLimit rateLimit,
        State state) {

    public ProviderView {
        Guard.notNull(providerId, "ProviderView.providerId");
        Guard.notNull(code, "ProviderView.code");
        Guard.notNull(channel, "ProviderView.channel");
        Guard.notNull(adapterType, "ProviderView.adapterType");
        Guard.notNull(rateLimit, "ProviderView.rateLimit");
        Guard.notNull(state, "ProviderView.state");
    }

    public Optional<Tariff> tariffOptional() {
        return Optional.ofNullable(tariff);
    }

    /**
     * Everything that changes at runtime (FR-2.6, FR-2.7, FR-6.3).
     *
     * @param selectable whether the router may currently pick this provider — the single flag that
     *     answers "why is nothing going out over it?"
     * @param endpointConfig transport settings interpreted by the adapter (§10.1)
     */
    public record State(
            boolean enabled,
            boolean maintenance,
            ProviderHealthStatus health,
            boolean selectable,
            QuotaConfig quota,
            String credentialsRef,
            Map<String, String> endpointConfig) {

        public State {
            Guard.notNull(health, "State.health");
            Guard.notNull(quota, "State.quota");
            endpointConfig = endpointConfig == null ? Map.of() : Map.copyOf(endpointConfig);
        }
    }
}
