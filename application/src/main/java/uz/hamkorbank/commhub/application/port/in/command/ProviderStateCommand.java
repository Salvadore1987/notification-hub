package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Switching a provider on, off or into maintenance at runtime (FR-2.7, AD-07).
 *
 * @param reason why, for the audit log and the admin panel (FR-7.3)
 */
public record ProviderStateCommand(Actor actor, ProviderId providerId, ProviderState state, String reason) {

    public ProviderStateCommand {
        Guard.notNull(actor, "ProviderStateCommand.actor");
        Guard.notNull(providerId, "ProviderStateCommand.providerId");
        Guard.notNull(state, "ProviderStateCommand.state");
    }

    public static ProviderStateCommand of(Actor actor, ProviderId providerId, ProviderState state) {
        return new ProviderStateCommand(actor, providerId, state, null);
    }

    public Optional<String> reasonOptional() {
        return Optional.ofNullable(reason);
    }

    /**
     * Operational state an operator can put a provider in (FR-2.7).
     *
     * <p>{@code MAINTENANCE} is not "disabled with a nicer name": a disabled provider is one the Bank
     * stopped using, a provider in maintenance is expected back, and the two are reported differently
     * in the admin panel and the health screens.
     */
    public enum ProviderState {
        ENABLED,
        DISABLED,
        MAINTENANCE
    }
}
