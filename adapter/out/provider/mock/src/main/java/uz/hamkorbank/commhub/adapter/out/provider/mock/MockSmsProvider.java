package uz.hamkorbank.commhub.adapter.out.provider.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.provider.support.OutboundContentLog;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.SmsProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;

/**
 * SMS through the fake provider of the local stand (ADR-0041).
 *
 * <p>Resolved exactly like any other adapter: {@code AdapterType} is an opaque string and the gateway
 * picks the bean whose type matches the routed provider, so this needs no change in the domain (AR-04).
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.mock", name = "enabled", havingValue = "true")
public class MockSmsProvider implements SmsProviderPort {

    /** Type to put on the provider profile so that routing lands here. */
    public static final AdapterType TYPE = AdapterType.of("mock-sms");

    private final MockProvider mock;
    private final OutboundContentLog contentLog;

    public MockSmsProvider(MockProvider mock, OutboundContentLog contentLog) {
        this.mock = mock;
        this.contentLog = contentLog;
    }

    @Override
    public AdapterType adapterType() {
        return TYPE;
    }

    @Override
    public ProviderAck submit(SmsSubmission submission) {
        contentLog.record(submission);
        return mock.answer(
                submission.provider(),
                submission.messageId(),
                submission.recipient().value(),
                submission.providerMessageIdOptional().orElse(null));
    }
}
