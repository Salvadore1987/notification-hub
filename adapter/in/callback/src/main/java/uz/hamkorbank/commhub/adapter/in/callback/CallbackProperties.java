package uz.hamkorbank.commhub.adapter.in.callback;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may push delivery reports, and how they prove it (PM-02, SG-04, SEC-07).
 *
 * <p>Per provider, keyed by the provider code. Playmobile and SMS Gate agree their own source
 * addresses and their own secret with the Bank, and one shared credential would mean a leak at either
 * of them lets the other's traffic be forged.
 *
 * <p>The secret ({@link Provider#secret()}) arrives as a value from the environment of the pod on any
 * contour (SG-04, ADR-0044) and is never logged.
 *
 * @param base path the endpoints hang under; kept out of {@code /api/v1}, which is the contract of the
 *     source systems and versioned on their schedule
 */
@ConfigurationProperties("commhub.callback")
public record CallbackProperties(String base, Map<String, Provider> providers) {

    public static final String DEFAULT_BASE = "/api/callbacks";

    public CallbackProperties {
        base = base == null || base.isBlank() ? DEFAULT_BASE : base;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    public static CallbackProperties defaults() {
        return new CallbackProperties(null, null);
    }

    /** Configuration of one provider, looked up case-insensitively by its code. */
    public Optional<Provider> providerOf(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return Optional.empty();
        }
        String wanted = providerCode.trim().toUpperCase(Locale.ROOT);
        return providers.entrySet().stream()
                .filter(entry -> entry.getKey().trim().toUpperCase(Locale.ROOT).equals(wanted))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * @param enabled whether this provider's callbacks are accepted at all
     * @param allowedIps addresses agreed with the provider; empty means the check is delegated to the
     *     network — an explicit decision an operator has to make, not a default
     * @param secretHeader header the shared secret arrives in
     * @param secret expected value of that header; empty disables the check, which is a decision an
     *     operator makes rather than something a missing property does quietly (SEC-04, SG-04)
     */
    public record Provider(Boolean enabled, List<String> allowedIps, String secretHeader, String secret) {

        public static final String DEFAULT_SECRET_HEADER = "X-Commhub-Callback-Secret";

        public Provider {
            enabled = enabled == null || enabled;
            allowedIps = allowedIps == null ? List.of() : List.copyOf(allowedIps);
            secretHeader = secretHeader == null || secretHeader.isBlank() ? DEFAULT_SECRET_HEADER : secretHeader;
        }

        public boolean checksAddress() {
            return !allowedIps.isEmpty();
        }

        public boolean checksSecret() {
            return secret != null && !secret.isBlank();
        }

        @Override
        public String toString() {
            return "Provider[enabled=%s, allowedIps=%s, secretHeader=%s, secret=***]"
                    .formatted(enabled, allowedIps, secretHeader);
        }
    }
}
