package uz.hamkorbank.commhub.adapter.out.provider.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.provider.support.OutboundContentLog;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.PushProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;

/**
 * Push through the fake provider of the local stand (ADR-0041).
 *
 * <p>Push is terminal at {@code SENT_TO_PROVIDER} (PU-12), so a token ending in {@code 00} gets no
 * delivery report — there is nothing for a real platform to report either. A token ending in
 * {@code 04} is the useful one here: it exercises the "dead token" path end to end.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.mock", name = "enabled", havingValue = "true")
public class MockPushProvider implements PushProviderPort {

    public static final AdapterType TYPE = AdapterType.of("mock-push");

    private final MockProvider mock;
    private final OutboundContentLog contentLog;

    public MockPushProvider(MockProvider mock, OutboundContentLog contentLog) {
        this.mock = mock;
        this.contentLog = contentLog;
    }

    @Override
    public AdapterType adapterType() {
        return TYPE;
    }

    /** Both platforms: on the stand the fake provider stands in for whichever the token names. */
    @Override
    public boolean supportsPlatform(PushPlatform platform) {
        return true;
    }

    @Override
    public ProviderAck submit(PushSubmission submission) {
        contentLog.record(submission);
        return mock.answer(
                submission.provider(),
                submission.messageId(),
                submission.token().value(),
                null);
    }
}
