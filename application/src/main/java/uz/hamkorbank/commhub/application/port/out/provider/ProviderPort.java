package uz.hamkorbank.commhub.application.port.out.provider;

import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/**
 * Common part of every channel output port (AR-04, MP-05).
 *
 * <p>The {@code Router} yields a {@link ProviderRef}; the application layer resolves the adapter that
 * serves it by matching {@link #adapterType()}, which is the only coupling between a route and a
 * concrete integration.
 */
public interface ProviderPort {

    /** Adapter this implementation provides, e.g. {@code playmobile-http} (§10.1 {@code provider}). */
    AdapterType adapterType();

    /** Channel the adapter delivers over. */
    Channel channel();

    /** Whether this adapter serves the routed provider. */
    default boolean supports(ProviderRef provider) {
        return provider != null
                && provider.channel() == channel()
                && provider.adapterType().equals(adapterType());
    }
}
