package uz.hamkorbank.commhub.domain.model.content;

import uz.hamkorbank.commhub.domain.model.type.Channel;

/**
 * Channel-specific payload of a message — sealed hierarchy of the Message pattern (SRS §5.2, MP-02).
 *
 * <p>The envelope is channel-independent (MP-01); everything that differs per channel lives here and
 * in the provider adapters. Adding a channel adds a new specialisation, the pipeline is untouched
 * (AR-05).
 */
public sealed interface MessageContent permits SmsContent, EmailContent, PushContent {

    /** Channel this payload can be delivered over. */
    Channel channel();

    /** Payload size in bytes; used by channel payload limits such as the 4 KB push limit (PU-11). */
    int payloadSizeBytes();
}
