package uz.hamkorbank.commhub.adapter.out.provider.mock;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.in.ProcessProviderStatus;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Delivery reports of the fake provider, delivered a moment after the send (ADR-0041, AD-06).
 *
 * <p>Without it the stand shows half a pipeline: the status stops at {@code SENT_TO_PROVIDER}, the
 * batch counters do not move and the "Сообщения" screen looks broken. A real provider reports later
 * and out of band, so the fake one does too — through the same use case a real DLR goes through.
 *
 * <p>The queue is in memory and deliberately not durable: this is a local stand, and a report lost to
 * a restart costs nothing. Reports are due after a short delay so that the two statuses are visibly
 * separate on the screen rather than collapsing into one.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.mock", name = "enabled", havingValue = "true")
public class MockDeliveryReports {

    private static final Logger LOG = LoggerFactory.getLogger(MockDeliveryReports.class);

    private final Queue<PendingReport> pending = new ConcurrentLinkedQueue<>();
    private final ProcessProviderStatus processStatus;
    private final ClockPort clock;
    private final MockProviderProperties properties;

    public MockDeliveryReports(
            ProcessProviderStatus processStatus, ClockPort clock, MockProviderProperties properties) {
        this.processStatus = Guard.notNull(processStatus, "processStatus");
        this.clock = Guard.notNull(clock, "clock");
        this.properties = Guard.notNull(properties, "properties");
    }

    /** Remembers that this message is to be reported on, once the provider "gets around to it". */
    public void schedule(ProviderCode provider, MessageId messageId, MockBehaviour behaviour) {
        if (behaviour != MockBehaviour.DELIVERED && behaviour != MockBehaviour.UNDELIVERED) {
            return;
        }
        pending.add(new PendingReport(
                provider, messageId, behaviour.reportedStatus(), clock.now().plus(properties.reportDelay())));
    }

    @Scheduled(
            fixedDelayString = "${commhub.provider.mock.report-interval:1s}",
            initialDelayString = "${commhub.provider.mock.report-interval:1s}")
    public void deliverDueReports() {
        Instant now = clock.now();
        PendingReport report = pending.peek();
        while (report != null && !report.dueAt().isAfter(now)) {
            pending.poll();
            apply(report, now);
            report = pending.peek();
        }
    }

    private void apply(PendingReport report, Instant now) {
        try {
            processStatus.process(new ProviderStatusCommand(
                    report.provider(),
                    null,
                    report.messageId(),
                    report.status(),
                    report.status().name(),
                    "mock provider delivery report",
                    null,
                    now));
        } catch (RuntimeException e) {
            // Стенд, а не продакшен: потерянный отчёт стоит одной строки в логе, а не тика.
            LOG.warn("Mock delivery report for {} was not applied", report.messageId(), e);
        }
    }

    private record PendingReport(ProviderCode provider, MessageId messageId, MessageStatus status, Instant dueAt) {}
}
