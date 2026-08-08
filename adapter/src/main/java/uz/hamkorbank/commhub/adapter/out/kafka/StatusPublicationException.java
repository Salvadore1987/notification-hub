package uz.hamkorbank.commhub.adapter.out.kafka;

import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;

/**
 * The broker did not acknowledge a status event (AD-03).
 *
 * <p>Not an error to handle here: the relay catches it, leaves the row unpublished with the reason in
 * {@code outbox_event.last_error}, and the event goes out again on a later pass.
 */
public class StatusPublicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StatusPublicationException(String topic, MessageStatusEvent event, Throwable cause) {
        super(
                "Failed to publish status event %s of message %s to %s"
                        .formatted(event.eventId(), event.key().messageId().value(), topic),
                cause);
    }
}
