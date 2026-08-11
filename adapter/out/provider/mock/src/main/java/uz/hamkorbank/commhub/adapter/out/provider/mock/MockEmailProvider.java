package uz.hamkorbank.commhub.adapter.out.provider.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.provider.EmailProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;

/**
 * Email through the fake provider of the local stand (ADR-0041).
 *
 * <p>The rule is read from the local part of the address, so {@code ivan00@example.com} is delivered
 * and {@code ivan02@example.com} never answers.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.mock", name = "enabled", havingValue = "true")
public class MockEmailProvider implements EmailProviderPort {

    public static final AdapterType TYPE = AdapterType.of("mock-email");

    private final MockProvider mock;

    public MockEmailProvider(MockProvider mock) {
        this.mock = mock;
    }

    @Override
    public AdapterType adapterType() {
        return TYPE;
    }

    @Override
    public ProviderAck submit(EmailSubmission submission) {
        return mock.answer(submission.provider(), submission.messageId(), localPart(submission), null);
    }

    /** {@code ivan02@example.com} → {@code ivan02}: the domain would hide the rule behind ".com". */
    private static String localPart(EmailSubmission submission) {
        String address = submission.recipient().value();
        int at = address.indexOf('@');
        return at > 0 ? address.substring(0, at) : address;
    }
}
