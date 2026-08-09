package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.ProviderHealthResult;
import uz.hamkorbank.commhub.application.port.in.CheckProviderHealth;
import uz.hamkorbank.commhub.application.port.in.command.CheckProviderHealthCommand;

/**
 * Ticks the provider health monitor (FR-6.3, PR-02).
 *
 * <p>Every transition is logged at WARN with the figures behind it: a provider leaving or re-entering
 * routing changes where the Bank's traffic goes, and that must be visible in the log of the instance
 * that decided it, not only in the admin panel (OBS-04).
 */
@Component
@ConditionalOnProperty(
        prefix = "commhub.provider.health",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProviderHealthScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderHealthScheduler.class);

    private final CheckProviderHealth monitor;

    public ProviderHealthScheduler(CheckProviderHealth monitor) {
        this.monitor = monitor;
    }

    @Scheduled(
            fixedDelayString = "${commhub.provider.health.interval:30s}",
            initialDelayString = "${commhub.provider.health.initial-delay:60s}")
    public void checkProviders() {
        try {
            ProviderHealthResult result = monitor.check(CheckProviderHealthCommand.allChannels());
            result.transitions()
                    .forEach(transition -> LOG.warn(
                            "provider {} health {} -> {} ({}); {}",
                            transition.provider().value(),
                            transition.from(),
                            transition.to(),
                            transition.detail(),
                            transition.isFailover() ? "routing fails over to the reserve" : "back in routing"));
        } catch (RuntimeException e) {
            // Пропущенный проход безопаснее остановленного планировщика: статусы останутся прежними.
            LOG.error("Provider health check failed; statuses stay as they are (FR-6.3)", e);
        }
    }
}
