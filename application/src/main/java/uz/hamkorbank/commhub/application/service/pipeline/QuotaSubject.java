package uz.hamkorbank.commhub.application.service.pipeline;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The three dimensions one send is counted against: stream, channel and provider (FR-2.6).
 *
 * <p>All three are needed at once because a quota is a ceiling on a dimension, not on a message: the
 * same SMS counts towards its stream's daily budget, its channel's monthly budget and the volume the
 * Bank contracted with that provider. Only the dimensions that are actually configured cost anything —
 * an unlimited quota is not read at all.
 *
 * @param channelConfig configuration of {@code channel}; {@code null} when the snapshot has none
 * @param provider provider the message was routed to; {@code null} before routing
 */
public record QuotaSubject(Stream stream, Channel channel, ChannelConfig channelConfig, Provider provider) {

    public QuotaSubject {
        Guard.notNull(stream, "QuotaSubject.stream");
        Guard.notNull(channel, "QuotaSubject.channel");
    }

    public static QuotaSubject of(Stream stream, Channel channel) {
        return new QuotaSubject(stream, channel, null, null);
    }

    public Optional<ChannelConfig> channelConfigOptional() {
        return Optional.ofNullable(channelConfig);
    }

    public Optional<Provider> providerOptional() {
        return Optional.ofNullable(provider);
    }
}
