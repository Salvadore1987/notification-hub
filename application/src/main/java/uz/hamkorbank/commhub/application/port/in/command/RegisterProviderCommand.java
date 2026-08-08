package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Registration of a delivery provider (FR-2.1).
 *
 * @param adapterType key of the adapter that serves it, e.g. {@code playmobile-http} (AR-04)
 * @param settings weight, tariff, limits and the reference to its credentials (SEC-04)
 * @param quota count and cost ceiling of this provider; {@code null} means unlimited (FR-2.6)
 * @param endpointConfig transport settings the adapter interprets; may be empty
 */
public record RegisterProviderCommand(
        Actor actor,
        ProviderCode code,
        Channel channel,
        AdapterType adapterType,
        Provider.Settings settings,
        QuotaConfig quota,
        Map<String, String> endpointConfig) {

    public RegisterProviderCommand {
        Guard.notNull(actor, "RegisterProviderCommand.actor");
        Guard.notNull(code, "RegisterProviderCommand.code");
        Guard.notNull(channel, "RegisterProviderCommand.channel");
        Guard.notNull(adapterType, "RegisterProviderCommand.adapterType");
        settings = settings == null ? Provider.Settings.defaults() : settings;
        endpointConfig = endpointConfig == null ? Map.of() : Map.copyOf(endpointConfig);
    }

    public Optional<QuotaConfig> quotaOptional() {
        return Optional.ofNullable(quota);
    }
}
