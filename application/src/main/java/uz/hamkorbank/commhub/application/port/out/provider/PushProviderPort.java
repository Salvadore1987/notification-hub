package uz.hamkorbank.commhub.application.port.out.provider;

import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;

/**
 * Push channel output port implemented by the FCM and APNs adapters (AR-04, PU-01, PU-06).
 *
 * <p>The adapter is selected by the platform of the device token, unless the channel is configured to
 * route iOS through FCM as well (PU-05, §9.4). Neither platform returns a delivery receipt, so an
 * accepted submission is terminal at {@code SENT_TO_PROVIDER} (PU-12).
 */
public interface PushProviderPort extends ProviderPort {

    @Override
    default Channel channel() {
        return Channel.PUSH;
    }

    /** Platform this adapter delivers to; drives adapter selection per token (§9.4). */
    boolean supportsPlatform(PushPlatform platform);

    ProviderAck submit(PushSubmission submission);
}
