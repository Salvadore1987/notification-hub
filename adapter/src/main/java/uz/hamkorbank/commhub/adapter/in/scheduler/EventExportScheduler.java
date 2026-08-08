package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.EventExportResult;
import uz.hamkorbank.commhub.application.port.in.ExportDeliveryEvents;
import uz.hamkorbank.commhub.application.port.in.command.ExportDeliveryEventsCommand;

/**
 * Ticks the data-mart export (FR-6.4).
 *
 * <p>Minutes rather than the relay's half-second: a mart is a batch consumer, and a feed that arrives
 * within a minute is early for everything downstream of it. The bounded pass in the use case decides how
 * much one tick moves.
 *
 * <p>Off by default. The topic and its schema are agreed with the data team before anything is published
 * to it, and a feed that starts itself the day the module is deployed would publish into a topic nobody
 * is reading yet.
 *
 * <p>A failed pass is logged and dropped: the cursor did not move, so the next tick republishes exactly
 * what this one did not.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.export.events", name = "enabled", havingValue = "true")
public class EventExportScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(EventExportScheduler.class);

    private final ExportDeliveryEvents export;
    private final EventExportProperties properties;

    public EventExportScheduler(ExportDeliveryEvents export, EventExportProperties properties) {
        this.export = export;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${commhub.export.events.interval:1m}",
            initialDelayString = "${commhub.export.events.interval:1m}")
    public void exportEvents() {
        try {
            EventExportResult result =
                    export.export(new ExportDeliveryEventsCommand(properties.pageSize(), properties.maxPages()));
            if (result.exported() > 0) {
                LOG.info(
                        "Exported {} delivery events to the data mart up to {} (FR-6.4){}",
                        result.exported(),
                        result.position(),
                        result.exhausted() ? "" : "; more is waiting for the next tick");
            }
        } catch (RuntimeException e) {
            LOG.error("Data-mart export pass failed; the cursor did not move and the page is retried (FR-6.4)", e);
        }
    }
}
