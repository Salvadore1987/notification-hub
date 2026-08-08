package uz.hamkorbank.commhub.domain.model.type;

/** Health of a provider integration, maintained by probes and passive metrics (PR-02, FR-6.3). */
public enum ProviderHealthStatus {
    UP,
    /** Thresholds on delivery/error rate or latency are breached; still usable. */
    DEGRADED,
    /** Circuit breaker open or probe failing; excluded from routing until failback. */
    DOWN,
    UNKNOWN;

    /** Whether the router may still select the provider. */
    public boolean selectable() {
        return this != DOWN;
    }
}
