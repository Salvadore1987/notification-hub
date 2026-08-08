package uz.hamkorbank.commhub.domain.model.type;

/** Liveness of a source system, derived from its last activity (FR-1.3). */
public enum ConnectionStatus {
    CONNECTED,
    IDLE,
    DISCONNECTED,
    /** No traffic has ever been observed. */
    UNKNOWN
}
