package uz.hamkorbank.commhub.adapter.in.admin.support;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.port.in.GetRoutingConfiguration;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Resolves the provider code an operator picked into the reference the domain stores (FR-1.3, FR-2.2).
 *
 * <p>A form offers a code, because that is what a person recognises; a {@code ProviderRef} carries the
 * id, the channel and the adapter type, because that is what routing needs without a second lookup. One
 * place bridges the two so that a code nobody registered is refused at the edge with a field pointer
 * (IR-01), rather than reaching a use case as a half-built reference.
 *
 * <p>It reads through the {@link GetRoutingConfiguration} use case rather than through the repository:
 * a driving adapter composes use cases, and reaching for an output port here would be the first place
 * the dependency rule bent (AR-01).
 */
@Component
public class ProviderRefs {

    private final GetRoutingConfiguration configuration;

    public ProviderRefs(GetRoutingConfiguration configuration) {
        this.configuration = Guard.notNull(configuration, "configuration");
    }

    /** The reference behind a provider code, or {@code null} when no code was given. */
    public ProviderRef resolve(String code, String field) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String wanted = code.trim();
        return configuration.providers().stream()
                .filter(provider -> provider.code().value().equals(wanted))
                .findFirst()
                .map(ProviderRefs::refOf)
                .orElseThrow(() -> InboundContractException.invalid(field, "no provider is registered as " + wanted));
    }

    private static ProviderRef refOf(ProviderView provider) {
        return new ProviderRef(provider.providerId(), provider.code(), provider.channel(), provider.adapterType());
    }
}
