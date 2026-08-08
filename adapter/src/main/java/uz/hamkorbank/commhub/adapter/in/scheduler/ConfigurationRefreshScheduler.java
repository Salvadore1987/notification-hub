package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.config.CachingProviderConfigRepository;

/**
 * Keeps the cached routing configuration fresh (AD-07, NF-07).
 *
 * <p>The cache expires on its own, so this scheduler is not what makes a change apply — it is what
 * keeps the cost of applying it off the sending path. Without it, the first message after every expiry
 * pays for reloading channels, providers and policies, and on the OTP path that is the one message with
 * a 200 ms budget (FR-1.7, TC-01).
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: a slow database must not queue up refreshes behind each
 * other. A failed refresh is not fatal — the previous snapshot stays in place and the next tick tries
 * again (the cache logs it).
 */
@Component
@ConditionalOnProperty(prefix = "commhub.config.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConfigurationRefreshScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationRefreshScheduler.class);

    private final CachingProviderConfigRepository cache;

    public ConfigurationRefreshScheduler(CachingProviderConfigRepository cache) {
        this.cache = cache;
    }

    @Scheduled(
            fixedDelayString = "${commhub.config.cache.refresh-interval:10s}",
            initialDelayString = "${commhub.config.cache.refresh-interval:10s}")
    public void refreshConfiguration() {
        try {
            cache.refresh();
        } catch (RuntimeException e) {
            // Пропущенный тик безопаснее остановленного планировщика: снапшот истечёт сам и перезагрузится.
            LOG.error("Routing configuration refresh tick failed; the snapshot expires on its own (AD-07)", e);
        }
    }
}
