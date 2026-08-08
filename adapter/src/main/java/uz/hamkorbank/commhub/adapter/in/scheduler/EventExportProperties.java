package uz.hamkorbank.commhub.adapter.in.scheduler;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.application.port.in.command.ExportDeliveryEventsCommand;

/**
 * Pace and size of the data-mart export (FR-6.4).
 *
 * @param enabled whether this instance exports at all; the feed is switched on once the topic and its
 *     schema are agreed with the data team
 * @param interval delay between passes; a mart is a batch consumer and does not need seconds
 * @param pageSize rows per page, bounding both the read and the Kafka batch
 * @param maxPages pages per tick, so a backlog is worked off over several ticks instead of one long run
 */
@ConfigurationProperties("commhub.export.events")
public record EventExportProperties(Boolean enabled, Duration interval, Integer pageSize, Integer maxPages) {

    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    public EventExportProperties {
        enabled = enabled != null && enabled;
        interval = interval == null || interval.isZero() || interval.isNegative() ? DEFAULT_INTERVAL : interval;
        pageSize = pageSize == null || pageSize < 1 ? ExportDeliveryEventsCommand.DEFAULT_PAGE_SIZE : pageSize;
        maxPages = maxPages == null || maxPages < 1 ? ExportDeliveryEventsCommand.DEFAULT_MAX_PAGES : maxPages;
    }

    public static EventExportProperties defaults() {
        return new EventExportProperties(null, null, null, null);
    }
}
