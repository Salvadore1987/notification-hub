package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;

/**
 * One pass of the provider health monitor (FR-6.3, PR-02).
 *
 * @param channel restrict the pass to one channel; {@code null} checks every provider
 */
public record CheckProviderHealthCommand(Channel channel) {

    public static CheckProviderHealthCommand allChannels() {
        return new CheckProviderHealthCommand(null);
    }

    public Optional<Channel> channelOptional() {
        return Optional.ofNullable(channel);
    }
}
