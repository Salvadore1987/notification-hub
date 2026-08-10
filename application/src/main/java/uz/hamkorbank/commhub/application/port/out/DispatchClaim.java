package uz.hamkorbank.commhub.application.port.out;

import java.time.Duration;
import java.time.Instant;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One claim a dispatcher makes on a page of messages of its traffic class (AD-04, TC-01, ADR-0039).
 *
 * <p>Rows are taken with {@code FOR UPDATE SKIP LOCKED} and stamped with a lease, which is what lets
 * every instance run the same dispatcher without two of them handing the same message to a provider —
 * the outbox relay's pattern applied to sending.
 *
 * <p>The lease outlives the transaction that took it on purpose. A row lock would be released the moment
 * the claiming transaction commits, and the provider call deliberately happens outside it (ADR-0039); an
 * instance killed between the two would otherwise leave the message claimed by nobody and picked up
 * immediately by the next pass, which is how one message becomes two SMS.
 *
 * @param owner instance identity written on the row; diagnostics for the duty shift, never correctness
 * @param now moment the claim is made; the lease and the send window are measured from it
 * @param lease how long the claim holds before the message returns to the queue on its own
 * @param limit how many messages this pass takes
 */
public record DispatchClaim(String owner, Instant now, Duration lease, int limit) {

    public DispatchClaim {
        Guard.notBlank(owner, "DispatchClaim.owner");
        Guard.notNull(now, "DispatchClaim.now");
        Guard.notNull(lease, "DispatchClaim.lease");
        Guard.isTrue(!lease.isNegative() && !lease.isZero(), "DispatchClaim.lease must be positive");
        Guard.positive(limit, "DispatchClaim.limit");
    }

    /** Moment the claim expires and the message may be taken by somebody else. */
    public Instant leaseUntil() {
        return now.plus(lease);
    }
}
