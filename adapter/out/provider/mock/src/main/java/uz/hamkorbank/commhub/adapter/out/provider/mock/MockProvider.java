package uz.hamkorbank.commhub.adapter.out.provider.mock;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
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
        if (ack.isAccepted()) {
            reports.schedule(provider.code(), messageId, behaviour);
        }
        LOG.info(
                "Mock provider {} applied rule {} to message {}",
                provider.code().value(),
                behaviour,
                messageId);
        return ack;
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
