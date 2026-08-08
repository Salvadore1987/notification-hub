package uz.hamkorbank.commhub.domain.model.vo;

import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reference to a provider produced by the router (MP-05).
 *
 * <p>The {@code Router} never knows concrete providers — it yields a {@code ProviderRef}, and the
 * application layer resolves the channel output port implementation from {@link #adapterType()}.
 */
public record ProviderRef(ProviderId id, ProviderCode code, Channel channel, AdapterType adapterType) {

    public ProviderRef {
        Guard.notNull(id, "ProviderRef.id");
        Guard.notNull(code, "ProviderRef.code");
        Guard.notNull(channel, "ProviderRef.channel");
        Guard.notNull(adapterType, "ProviderRef.adapterType");
    }

    @Override
    public String toString() {
        return code.value() + "@" + channel;
    }
}
