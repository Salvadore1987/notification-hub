package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Map;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Change of a provider profile (FR-2.1, FR-2.5, FR-2.6).
 *
 * <p>Every optional field is a patch: {@code null} leaves the current value alone, which is what lets
 * the admin panel change one weight without resubmitting the tariff and the credentials reference.
 * Clearing a tariff or a quota is expressed by an explicit "unlimited"/zero value, not by {@code null}.
 *
 * @param endpointConfig complete replacement of the adapter's transport settings; {@code null} keeps
 *     the stored map (§10.1 {@code provider.endpoint_config})
 */
public record UpdateProviderCommand(
        Actor actor,
        ProviderId providerId,
        Integer weight,
        Tariff tariff,
        RateLimit rateLimit,
        QuotaConfig quota,
        Map<String, String> endpointConfig) {

    public UpdateProviderCommand {
        Guard.notNull(actor, "UpdateProviderCommand.actor");
        Guard.notNull(providerId, "UpdateProviderCommand.providerId");
        endpointConfig = endpointConfig == null ? null : Map.copyOf(endpointConfig);
    }
}
