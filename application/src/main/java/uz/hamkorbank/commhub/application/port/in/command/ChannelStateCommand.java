package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Switching a whole channel on, off or into maintenance at runtime (FR-2.7, AD-07). */
public record ChannelStateCommand(Actor actor, Channel channel, ChannelStatus status, String reason) {

    public ChannelStateCommand {
        Guard.notNull(actor, "ChannelStateCommand.actor");
        Guard.notNull(channel, "ChannelStateCommand.channel");
        Guard.notNull(status, "ChannelStateCommand.status");
    }

    public static ChannelStateCommand of(Actor actor, Channel channel, ChannelStatus status) {
        return new ChannelStateCommand(actor, channel, status, null);
    }

    public Optional<String> reasonOptional() {
        return Optional.ofNullable(reason);
    }
}
