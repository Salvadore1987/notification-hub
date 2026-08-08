package uz.hamkorbank.commhub.application.port.out;

import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Dimension a quota is counted in: stream, channel and/or provider (FR-2.6).
 *
 * <p>A {@code null} component means "any", so {@code (stream, null, null)} is the quota of the whole
 * stream and {@code (null, SMS, playmobile)} the quota of one provider on one channel.
 */
public record QuotaScope(StreamId streamId, Channel channel, ProviderId providerId) {

    public QuotaScope {
        Guard.isTrue(
                streamId != null || channel != null || providerId != null,
                "QuotaScope requires a streamId, a channel or a providerId");
    }

    public static QuotaScope ofStream(StreamId streamId) {
        return new QuotaScope(streamId, null, null);
    }

    public static QuotaScope ofStreamChannel(StreamId streamId, Channel channel) {
        return new QuotaScope(streamId, channel, null);
    }

    public static QuotaScope ofProvider(Channel channel, ProviderId providerId) {
        return new QuotaScope(null, channel, providerId);
    }
}
