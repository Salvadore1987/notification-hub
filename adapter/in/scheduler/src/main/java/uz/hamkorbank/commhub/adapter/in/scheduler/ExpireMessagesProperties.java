package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.application.port.in.command.ExpireMessagesCommand;

/**
 * Pace of the TTL sweep (FR-3.4).
 *
 * @param limit messages one pass may expire; keeps the transaction of the sweep short
 * @param maxPassesPerTick back-to-back passes while the limit keeps being hit — a backlog after an
 *     outage drains within a tick instead of one page per interval
 */
@ConfigurationProperties("commhub.dispatch.expiry")
public record ExpireMessagesProperties(Boolean enabled, Integer limit, Integer maxPassesPerTick) {

    public ExpireMessagesProperties {
        enabled = enabled == null || enabled;
        limit = limit == null ? ExpireMessagesCommand.DEFAULT_LIMIT : limit;
        maxPassesPerTick = maxPassesPerTick == null ? 10 : maxPassesPerTick;
        if (limit < 1) {
            throw new IllegalArgumentException("commhub.dispatch.expiry.limit must be positive");
        }
        if (maxPassesPerTick < 1) {
            throw new IllegalArgumentException("commhub.dispatch.expiry.max-passes-per-tick must be positive");
        }
    }

    public static ExpireMessagesProperties defaults() {
        return new ExpireMessagesProperties(null, null, null);
    }
}
