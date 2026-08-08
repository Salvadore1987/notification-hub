package uz.hamkorbank.commhub.application.port.in.command;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Configuration of one channel: balancing, primary and reserve providers (FR-2.2, FR-2.3, FR-2.6).
 *
 * <p>{@code fallbackOrder} is the whole ordered list, primary first — the admin panel edits an order,
 * not a set of flags, and a partial update of an order is ambiguous. Providers are named by code:
 * a code is what an operator sees and it outlives the row it points at.
 */
public record ConfigureChannelCommand(
        Actor actor,
        Channel channel,
        BalancingStrategy balancingStrategy,
        List<ProviderCode> fallbackOrder,
        QuietHours quietHours,
        QuotaConfig quota) {

    public ConfigureChannelCommand {
        Guard.notNull(actor, "ConfigureChannelCommand.actor");
        Guard.notNull(channel, "ConfigureChannelCommand.channel");
        Guard.notNull(balancingStrategy, "ConfigureChannelCommand.balancingStrategy");
        fallbackOrder = Guard.copyOf(fallbackOrder);
    }
}
