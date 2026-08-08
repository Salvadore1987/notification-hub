package uz.hamkorbank.commhub.domain.model;

import uz.hamkorbank.commhub.domain.model.type.ActorType;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Who caused a state change; stored with every status history entry (ST-01, FR-7.3).
 *
 * @param id user login, provider code or stream id, depending on {@link #type()}; {@code null} for
 *     the Hub itself
 */
public record Actor(ActorType type, String id) {

    private static final Actor SYSTEM = new Actor(ActorType.SYSTEM, null);

    public static final int MAX_ID_LENGTH = 128;

    public Actor {
        Guard.notNull(type, "Actor.type");
        Guard.maxLength(id, MAX_ID_LENGTH, "Actor.id");
        Guard.isTrue(type == ActorType.SYSTEM || (id != null && !id.isBlank()), "Actor.id is required for " + type);
    }

    /** The Hub itself: pipeline stages, schedulers, retry policies. */
    public static Actor system() {
        return SYSTEM;
    }

    /** An admin-panel user (FR-3.2, FR-3.3, FR-7.3). */
    public static Actor operator(String userId) {
        return new Actor(ActorType.OPERATOR, userId);
    }

    /** A provider callback or a reconciliation run (PM-02, SG-02, SG-03). */
    public static Actor provider(String providerCode) {
        return new Actor(ActorType.PROVIDER, providerCode);
    }

    /** A source system (submission, cancellation). */
    public static Actor sourceSystem(String streamId) {
        return new Actor(ActorType.SOURCE_SYSTEM, streamId);
    }

    @Override
    public String toString() {
        return id == null ? type.name() : type + ":" + id;
    }
}
