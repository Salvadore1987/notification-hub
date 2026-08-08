package uz.hamkorbank.commhub.application.port.out.provider;

import uz.hamkorbank.commhub.domain.model.type.Channel;

/**
 * Email channel output port implemented by the SMTP adapter (AR-04, EM-01).
 *
 * <p>Hard bounces do not arrive here — they are reported asynchronously and land in the suppression
 * list through the bounce processor (EM-02).
 */
public interface EmailProviderPort extends ProviderPort {

    @Override
    default Channel channel() {
        return Channel.EMAIL;
    }

    ProviderAck submit(EmailSubmission submission);
}
