package uz.hamkorbank.commhub.adapter.out.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.application.policy.SendingPolicy;

/**
 * Retry and failover budget of the sending saga (PR-01, FR-2.2, FR-3.3).
 *
 * <p>This is the outer budget: how many attempts a message gets in total and how many of them on one
 * provider before the saga fails over. The inner one — the retry inside a single provider call — lives
 * in {@code commhub.provider.<code>.resilience} and is measured in hundreds of milliseconds.
 *
 * @param maxAttemptsPerProvider attempts on one provider before failing over to the next
 * @param maxTotalAttempts attempts across all providers before the message goes to the DLQ (FR-3.3)
 * @param initialBackoff delay before the first retry
 * @param backoffMultiplier growth factor of that delay
 * @param maxBackoff upper bound of the delay
 */
@ConfigurationProperties("commhub.sending")
public record SendingProperties(
        Integer maxAttemptsPerProvider,
        Integer maxTotalAttempts,
        Duration initialBackoff,
        Double backoffMultiplier,
        Duration maxBackoff) {

    public SendingPolicy toPolicy() {
        SendingPolicy defaults = SendingPolicy.defaults();
        return new SendingPolicy(
                maxAttemptsPerProvider == null ? defaults.maxAttemptsPerProvider() : maxAttemptsPerProvider,
                maxTotalAttempts == null ? defaults.maxTotalAttempts() : maxTotalAttempts,
                initialBackoff == null ? defaults.initialBackoff() : initialBackoff,
                backoffMultiplier == null ? defaults.backoffMultiplier() : backoffMultiplier,
                maxBackoff == null ? defaults.maxBackoff() : maxBackoff);
    }
}
