package uz.hamkorbank.commhub.domain.model;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Base class of every aggregate root: identity-based equality and an immutable identifier.
 *
 * <p>Aggregates are plain Java classes — no framework annotations anywhere in the domain (AR-02).
 *
 * @param <I> type of the aggregate identifier
 */
public abstract class AggregateRoot<I> {

    private final I id;

    protected AggregateRoot(I id) {
        this.id = Guard.notNull(id, "id");
    }

    public I id() {
        return id;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return id.equals(((AggregateRoot<?>) other).id);
    }

    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + id + "]";
    }
}
