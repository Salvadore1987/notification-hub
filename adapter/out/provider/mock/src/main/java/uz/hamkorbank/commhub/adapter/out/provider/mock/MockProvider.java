package uz.hamkorbank.commhub.adapter.out.provider.mock;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * The one place the fake provider decides anything; the three channel adapters only unwrap addresses
 * (ADR-0041).
 *
 * <p>The address is never logged, masked or otherwise — the rule is read off its last two characters
 * and the rest is of no interest to this class (SEC-06).
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.mock", name = "enabled", havingValue = "true")
public class MockProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MockProvider.class);

    private final ClockPort clock;
    private final MockProviderProperties properties;
    private final MockDeliveryReports reports;

    public MockProvider(ClockPort clock, MockProviderProperties properties, MockDeliveryReports reports) {
        this.clock = Guard.notNull(clock, "clock");
        this.properties = Guard.notNull(properties, "properties");
        this.reports = Guard.notNull(reports, "reports");
    }

    /**
     * Answers one submission according to the rule the address carries.
     *
     * @param providerMessageId identifier the Hub generated, when it did; otherwise the fake provider
     *     assigns one of its own, the way SMS Gate does
     */
    public ProviderAck answer(
            ProviderRef provider, MessageId messageId, String address, ProviderMessageId providerMessageId) {
        MockBehaviour behaviour = MockBehaviour.of(address);
        pretendToWork();
        Instant now = clock.now();
        ProviderAck ack = behaviour.ackFor(assignedId(providerMessageId), now);
        if (ack.isAccepted() && reportsFor(provider)) {
            reports.schedule(provider.code(), messageId, behaviour);
        }
        LOG.info(
                "Mock provider {} applied rule {} to message {}",
                provider.code().value(),
                behaviour,
                messageId);
        return ack;
    }

    /**
     * Whether this channel gets a delivery report at all (PU-12).
     *
     * <p>Push does not, and that is not a simplification of the stand: neither APNs nor FCM reports
     * delivery, so {@code SENT_TO_PROVIDER} is where a push message ends. A fake report moved it on to
     * {@code DELIVERED} — a status the same message can never reach on a real contour, which is the one
     * thing a stand must not teach.
     */
    private static boolean reportsFor(ProviderRef provider) {
        return provider.channel() != Channel.PUSH;
    }

    /** A call that returns instantly makes every latency panel on the stand read zero. */
    private void pretendToWork() {
        if (properties.latency().isZero()) {
            return;
        }
        try {
            Thread.sleep(properties.latency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ProviderMessageId assignedId(ProviderMessageId providerMessageId) {
        return providerMessageId != null
                ? providerMessageId
                : ProviderMessageId.of("MOCK" + UuidV7.generate().toString().substring(0, 16));
    }
}
