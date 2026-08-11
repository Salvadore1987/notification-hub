package uz.hamkorbank.commhub.adapter.out.provider.mock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the fake provider (ADR-0041).
 *
 * @param enabled off unless it is switched on explicitly — and there is no {@code matchIfMissing} on
 *     the beans, so a built image has no fake provider in it at all
 * @param latency how long a "call" appears to take; small but non-zero, so latency panels have
 *     something to show on the stand
 * @param reportDelay how long after the send the delivery report arrives
 */
@ConfigurationProperties("commhub.provider.mock")
public record MockProviderProperties(Boolean enabled, Duration latency, Duration reportDelay) {

    public MockProviderProperties {
        enabled = enabled != null && enabled;
        latency = latency == null ? Duration.ofMillis(50) : latency;
        reportDelay = reportDelay == null ? Duration.ofSeconds(3) : reportDelay;
        if (latency.isNegative() || reportDelay.isNegative()) {
            throw new IllegalArgumentException("commhub.provider.mock delays must not be negative");
        }
    }

    public static MockProviderProperties defaults() {
        return new MockProviderProperties(null, null, null);
    }
}
