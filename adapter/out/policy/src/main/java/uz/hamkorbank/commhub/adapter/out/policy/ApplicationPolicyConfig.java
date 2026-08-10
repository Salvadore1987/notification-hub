package uz.hamkorbank.commhub.adapter.out.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.hamkorbank.commhub.application.policy.DeduplicationPolicy;
import uz.hamkorbank.commhub.application.policy.ProviderHealthPolicy;
import uz.hamkorbank.commhub.application.policy.SendingPolicy;

/**
 * Builds the policies the pipeline and the saga read from the deployment settings (FR-1.5, PR-01,
 * FR-6.3).
 *
 * <p>The counterpart of {@code adapter/out/compliance} for the three policies that are not about
 * compliance: the idempotency window, the retry and failover budget, and the health thresholds. Each is
 * logged at startup for the same reason the compliance ones are — the numbers decide what happens during
 * an incident, and an operator reading the log should not have to reconstruct them from a ConfigMap.
 */
@Configuration
public class ApplicationPolicyConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationPolicyConfig.class);

    @Bean
    public DeduplicationPolicy deduplicationPolicy(DedupProperties properties) {
        DeduplicationPolicy policy = properties.toPolicy();
        LOG.info("Deduplication window (FR-1.5): {}", policy.window());
        return policy;
    }

    @Bean
    public SendingPolicy sendingPolicy(SendingProperties properties) {
        SendingPolicy policy = properties.toPolicy();
        LOG.info(
                "Sending budget (PR-01, FR-3.3): {} attempts per provider, {} in total, backoff {} x{} up to {}",
                policy.maxAttemptsPerProvider(),
                policy.maxTotalAttempts(),
                policy.initialBackoff(),
                policy.backoffMultiplier(),
                policy.maxBackoff());
        return policy;
    }

    @Bean
    public ProviderHealthPolicy providerHealthPolicy(ProviderHealthProperties properties) {
        ProviderHealthPolicy policy = properties.toPolicy();
        LOG.info(
                "Provider health (FR-6.3, PR-02): window {}, from {} attempts, DEGRADED at {}, "
                        + "DOWN at {} errors or {} timeouts, probation after {}",
                policy.window(),
                policy.minimumAttempts(),
                policy.degradedErrorRate(),
                policy.downErrorRate(),
                policy.downTimeoutRate(),
                policy.recoveryAfter());
        return policy;
    }
}
