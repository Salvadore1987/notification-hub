package uz.hamkorbank.commhub.adapter.in.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.in.DispatchMessage;
import uz.hamkorbank.commhub.application.port.in.command.DispatchMessageCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DispatchClaim;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What finally takes an accepted message and hands it to a provider (AD-04, TC-01, ADR-0039).
 *
 * <p>One scheduled method per traffic class, which is the point rather than a detail: a bulk campaign
 * drains through its own ticks, its own pages and its own semaphore, so it cannot take turns away from
 * OTP. The javadoc of {@code DispatchMessage} has described this arrangement since the saga was written;
 * until now nothing implemented it, and a deployed Hub accepted messages without sending any.
 *
 * <p>A pass claims a page with {@code SKIP LOCKED} and a lease, then runs one virtual thread per message
 * bounded by a semaphore. The saga holds no transaction across the provider call, so a call in flight
 * occupies no connection — which is why concurrency may exceed the pool size.
 *
 * <p>{@code fixedDelay}, never {@code fixedRate}: passes of one instance must not overlap. Overlap
 * <em>between</em> instances is fine and expected — that is what the claim is for.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MessageDispatchScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(MessageDispatchScheduler.class);

    /** How long a pass waits for its own sends before giving the tick back. */
    private static final Duration PASS_TIMEOUT = Duration.ofMinutes(5);

    private final MessageRepository messages;
    private final DispatchMessage dispatcher;
    private final ClockPort clock;
    private final MessageDispatchProperties properties;
    private final String owner;

    public MessageDispatchScheduler(
            MessageRepository messages,
            DispatchMessage dispatcher,
            ClockPort clock,
            MessageDispatchProperties properties) {
        this.messages = Guard.notNull(messages, "messages");
        this.dispatcher = Guard.notNull(dispatcher, "dispatcher");
        this.clock = Guard.notNull(clock, "clock");
        this.properties = Guard.notNull(properties, "properties");
        this.owner = ownerOf(properties);
    }

    @Scheduled(
            fixedDelayString = "${commhub.dispatch.critical-otp.poll-interval:200ms}",
            initialDelayString = "${commhub.dispatch.critical-otp.poll-interval:200ms}")
    public void dispatchCriticalOtp() {
        drain(TrafficClass.CRITICAL_OTP, properties.criticalOtp());
    }

    @Scheduled(
            fixedDelayString = "${commhub.dispatch.transactional.poll-interval:500ms}",
            initialDelayString = "${commhub.dispatch.transactional.poll-interval:500ms}")
    public void dispatchTransactional() {
        drain(TrafficClass.TRANSACTIONAL, properties.transactional());
    }

    @Scheduled(
            fixedDelayString = "${commhub.dispatch.notification.poll-interval:1s}",
            initialDelayString = "${commhub.dispatch.notification.poll-interval:1s}")
    public void dispatchNotification() {
        drain(TrafficClass.NOTIFICATION, properties.notification());
    }

    /** Drains one traffic class while its pages keep coming back full. */
    private void drain(TrafficClass trafficClass, MessageDispatchProperties.Pace pace) {
        for (int pass = 0; pass < pace.maxPassesPerTick(); pass++) {
            int claimed = runPass(trafficClass, pace);
            if (claimed < pace.batchSize()) {
                return;
            }
        }
    }

    /**
     * One pass: claim a page, send it, return how much was claimed.
     *
     * <p>A failure of one message must not cost the rest of the page, and a failure of the pass must not
     * stop the scheduler: an uncaught exception would silence the tick for good, and a Hub that stopped
     * sending is the outage this class exists to prevent.
     */
    private int runPass(TrafficClass trafficClass, MessageDispatchProperties.Pace pace) {
        List<MessageId> claimed;
        try {
            claimed = messages.claimDispatchable(
                    trafficClass, new DispatchClaim(owner, clock.now(), properties.lease(), pace.batchSize()));
        } catch (RuntimeException e) {
            LOG.error("Dispatch claim failed for {}; the next tick retries and nothing is lost", trafficClass, e);
            return 0;
        }
        if (claimed.isEmpty()) {
            return 0;
        }
        send(claimed, pace);
        return claimed.size();
    }

    /** Sends the claimed page, one virtual thread per message, bounded by the class's semaphore. */
    private void send(List<MessageId> claimed, MessageDispatchProperties.Pace pace) {
        Semaphore inFlight = new Semaphore(pace.concurrency());
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (MessageId messageId : claimed) {
                workers.submit(() -> dispatchOne(messageId, inFlight));
            }
            workers.shutdown();
            if (!workers.awaitTermination(PASS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                LOG.warn("Dispatch pass did not finish within {}; leases will return the rest", PASS_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Dispatch pass interrupted; claimed messages return to the queue when their lease elapses");
        }
    }

    private void dispatchOne(MessageId messageId, Semaphore inFlight) {
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            dispatcher.dispatch(DispatchMessageCommand.of(messageId));
        } catch (RuntimeException e) {
            // Аренда истечёт сама, и сообщение вернётся в очередь: терять его нельзя (AD-03).
            LOG.error("Dispatch of message {} failed; it returns when its lease elapses", messageId, e);
        } finally {
            inFlight.release();
        }
    }

    /** Who this instance says it is on the rows it claims; diagnostics only (ADR-0039). */
    private static String ownerOf(MessageDispatchProperties properties) {
        if (properties.owner() != null && !properties.owner().isBlank()) {
            return properties.owner();
        }
        String hostname = System.getenv("HOSTNAME");
        return hostname == null || hostname.isBlank()
                ? "commhub-" + ProcessHandle.current().pid()
                : hostname;
    }
}
