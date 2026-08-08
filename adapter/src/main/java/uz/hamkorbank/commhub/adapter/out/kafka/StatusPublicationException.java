package uz.hamkorbank.commhub.adapter.out.kafka;

/**
 * The broker did not acknowledge an outbound event (AD-03).
 *
 * <p>Not an error to handle here: the relay catches it, leaves the row unpublished with the reason in
 * {@code outbox_event.last_error}, and the event goes out again on a later pass.
 *
 * <p>Names the event by its id only. The message it belongs to is one join away in the outbox row, and
 * a publication failure of a push-token event has no message at all (PU-04).
 */
public class StatusPublicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StatusPublicationException(String topic, String eventId, Throwable cause) {
        super("Failed to publish event %s to %s".formatted(eventId, topic), cause);
    }
}
