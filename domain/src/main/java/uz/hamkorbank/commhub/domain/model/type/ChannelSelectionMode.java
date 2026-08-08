package uz.hamkorbank.commhub.domain.model.type;

/** How the delivery channel of a message is chosen (MP-03, FR-8.1). */
public enum ChannelSelectionMode {

    /** The source system names exactly one channel. */
    EXPLICIT,
    /** The Hub picks the channel from routing policies and stream defaults. */
    MODULE_CHOICE,
    /** Ordered chain, the next channel is tried when the previous one does not deliver. */
    FALLBACK_CHAIN
}
