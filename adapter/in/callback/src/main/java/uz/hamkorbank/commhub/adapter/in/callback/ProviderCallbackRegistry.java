package uz.hamkorbank.commhub.adapter.in.callback;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;

/**
 * The translators this build knows, indexed by provider code (AR-04).
 *
 * <p>Populated by injection, so a provider adapter joins the callback endpoint by existing as a bean —
 * the registry itself never learns about any particular provider. A provider that is configured but
 * whose adapter is switched off contributes no translator, and the endpoint answers 404 rather than
 * pretending to accept reports it cannot interpret.
 */
@Component
public class ProviderCallbackRegistry {

    private final Map<String, ProviderCallbackTranslator> byCode;

    public ProviderCallbackRegistry(List<ProviderCallbackTranslator> translators) {
        Map<String, ProviderCallbackTranslator> index = new HashMap<>();
        for (ProviderCallbackTranslator translator : translators) {
            ProviderCode code = translator.providerCode();
            ProviderCallbackTranslator previous = index.put(key(code.value()), translator);
            if (previous != null) {
                throw new IllegalStateException("Two callback translators claim provider %s: %s and %s"
                        .formatted(
                                code,
                                previous.getClass().getName(),
                                translator.getClass().getName()));
            }
        }
        this.byCode = Map.copyOf(index);
    }

    public Optional<ProviderCallbackTranslator> find(String providerCode) {
        return providerCode == null ? Optional.empty() : Optional.ofNullable(byCode.get(key(providerCode)));
    }

    /** Provider codes with a translator; used by the tests and by the readiness of the endpoint. */
    public Set<String> providerCodes() {
        return byCode.keySet();
    }

    private static String key(String providerCode) {
        return providerCode.trim().toUpperCase(Locale.ROOT);
    }
}
