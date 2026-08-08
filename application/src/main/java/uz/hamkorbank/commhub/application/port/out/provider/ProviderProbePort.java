package uz.hamkorbank.commhub.application.port.out.provider;

import java.time.Duration;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Synthetic health probe of a provider (PR-02).
 *
 * <p>The active half of health detection, and the optional one: PR-02 asks for "a synthetic probe
 * and/or passive metrics". <b>No SMS adapter implements it in the MVP</b> — neither Playmobile nor
 * SMS Gate publishes a status endpoint (§9.1, §9.2), and the only synthetic call available is a real,
 * chargeable message to a real number. The health monitor therefore runs on the passive figures of
 * {@code ProviderStatsPort} and picks probes up automatically once a channel that has one arrives
 * (SMTP {@code NOOP}, FCM/APNs token validation).
 *
 * <p>An implementation must never throw: a probe that fails is a probe that answers "not healthy".
 */
public interface ProviderProbePort {

    /** Whether this probe serves the given provider; matched by {@code adapterType} like the ports. */
    boolean supports(ProviderRef provider);

    ProbeResult probe(ProviderRef provider);

    /**
     * Answer of one probe.
     *
     * @param detail what was observed; shown to the operator next to the health status
     * @param latency round trip of the probe; {@code Duration.ZERO} when it did not get that far
     */
    record ProbeResult(boolean healthy, String detail, Duration latency) {

        public ProbeResult {
            Guard.notNull(latency, "ProbeResult.latency");
        }

        public static ProbeResult healthy(Duration latency) {
            return new ProbeResult(true, null, latency);
        }

        public static ProbeResult unhealthy(String detail) {
            return new ProbeResult(false, detail, Duration.ZERO);
        }
    }
}
