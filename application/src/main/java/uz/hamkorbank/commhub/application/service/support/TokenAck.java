package uz.hamkorbank.commhub.application.service.support;

import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;

/**
 * What one device answered inside a push fan-out (PU-09).
 *
 * <p>A type of its own rather than a record nested in {@link PushFanOut}, because the fan-out and
 * {@link PushDeliveryJournal} both work with these answers: one aggregates them into the single verdict
 * the saga acts on, the other turns them into rows.
 */
record TokenAck(PushToken token, ProviderAck ack) {

    /**
     * The provider's own word about this device — what the row and the attempt's description carry.
     *
     * <p>Description first, code second, verdict last: the further one falls back, the less the operator
     * is told, and an empty cell is the one answer that helps nobody.
     */
    String reason() {
        if (ack.errorDescription() != null && !ack.errorDescription().isBlank()) {
            return ack.errorDescription();
        }
        return ack.responseCode() == null ? ack.result().name() : ack.responseCode();
    }
}
