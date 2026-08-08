package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * Per-device record of a push fan-out (§10.1 {@code push_delivery}, PU-09).
 *
 * <p>Written by the fan-out inside the transaction of the sending saga, so a device row that survives a
 * rolled-back attempt cannot exist.
 */
public interface PushDeliveryLogPort {

    /** Records the outcome of every device of one fan-out; an empty list is a no-op. */
    void record(List<PushDelivery> deliveries);

    /** Devices of one message, for the message card of the admin panel (§11.2, PU-09). */
    List<PushDelivery> findByMessage(MessageId messageId);
}
