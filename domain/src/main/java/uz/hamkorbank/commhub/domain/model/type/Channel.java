package uz.hamkorbank.commhub.domain.model.type;

/**
 * Delivery channel of a message (SRS §6.4, AR-05).
 *
 * <p>Adding a channel means: a new {@code MessageContent} specialisation, a new channel output port,
 * provider adapters and channel configuration in the database — the pipeline itself is untouched
 * (AR-05). Planned extensions: INAPP, STORIES, WHATSAPP, TELEGRAM, VIBER, RCS, WEB_PUSH (FR-8.10).
 */
public enum Channel {
    SMS,
    EMAIL,
    PUSH;

    /** Whether the channel can report a delivery receipt; push cannot (PU-12). */
    public boolean supportsDeliveryReceipt() {
        return this != PUSH;
    }
}
