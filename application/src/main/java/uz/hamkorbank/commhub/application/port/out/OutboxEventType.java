package uz.hamkorbank.commhub.application.port.out;

/** Kind of outbox event; the relay maps it onto an outbound topic (§8.1 IK-02). */
public enum OutboxEventType {

    /** Canonical status update for the source systems → {@code comm.outbound.status.v1} (§6.4). */
    MESSAGE_STATUS,
    /** The message was moved to the DLQ → {@code comm.outbound.dlq.v1} (FR-3.3). */
    MESSAGE_DLQ,
    /**
     * A device token was declared dead by the platform → {@code comm.outbound.push-token.invalidated.v1}
     * (PU-04, PU-08).
     *
     * <p>Not a status of a message: the message it was discovered on has its own status, and this says
     * something about the address that outlives it.
     */
    PUSH_TOKEN_INVALIDATED
}
