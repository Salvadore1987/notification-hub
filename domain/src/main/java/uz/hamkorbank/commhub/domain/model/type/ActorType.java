package uz.hamkorbank.commhub.domain.model.type;

/** Who caused a status change; recorded in the status history (ST-01, FR-7.3). */
public enum ActorType {
    /** The Hub itself (pipeline stage, scheduler, retry policy). */
    SYSTEM,
    /** An admin-panel user (FR-3.2, FR-3.3). */
    OPERATOR,
    /** A provider callback or reconciliation result (PM-02, SG-02). */
    PROVIDER,
    /** A source system (submission, cancellation). */
    SOURCE_SYSTEM
}
