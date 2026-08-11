package uz.hamkorbank.commhub.application.service.support;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.ActorType;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Records that somebody looked at a customer's message (SEC-08, FR-7.3).
 *
 * <p>Only people are recorded. A source system polling the status of the message it submitted is the
 * normal traffic of §8.2 — auditing it would add a row per poll, drown the journal the auditor reads and
 * answer a question nobody asked. What SEC-08 is about is the other case: an employee opening a
 * customer's message in the admin panel, which is access to personal data whether or not anything was
 * changed.
 *
 * <p>The entry carries no address and no content, only the identifiers. The journal names <em>who looked
 * at whose</em>; it is not a second copy of what they saw.
 */
@Component
public class PersonalDataAccess {

    /** Entity type of the audit entry; the same word the message endpoints use (§10.1). */
    public static final String MESSAGE = "message";

    /** Action verb of a read; deliberately distinct from every verb that changes something. */
    public static final String ACTION_VIEW = "message.view";

    /** Action verb of a list; the entity id is the filter rather than one message (SEC-08). */
    public static final String ACTION_SEARCH = "message.search";

    private final AuditPort audit;
    private final ClockPort clock;

    public PersonalDataAccess(AuditPort audit, ClockPort clock) {
        this.audit = Guard.notNull(audit, "audit");
        this.clock = Guard.notNull(clock, "clock");
    }

    /**
     * Journals a read of one message when an operator, rather than a system, performed it.
     *
     * <p>Only the message is named. Which customer it was addressed to is a join away in a system that
     * keeps the message, and repeating the recipient here would put an address into a table that is kept
     * for years and may never be deleted (SEC-06, DB-03).
     */
    public void recordMessageView(Actor actor, MessageId messageId) {
        if (actor == null || actor.type() != ActorType.OPERATOR) {
            return;
        }
        audit.write(AuditEntry.of(
                actor, ACTION_VIEW, MESSAGE, messageId == null ? null : messageId.toString(), clock.now()));
    }

    /**
     * Journals a search over the message list (SEC-08).
     *
     * <p>A list of a customer's messages is access to their data as much as one card is — more of it, in
     * fact — so the same rule applies. One row per search, not per result: an auditor needs to see that
     * somebody looked a number up, and a row per hit would make one wide search look like a hundred
     * separate reads.
     *
     * <p>The entity of such a row is the <em>address hash</em>, not a message id, which is what makes
     * "who has been looking at this customer" one indexed query over {@code (entity_type, entity_id)} —
     * the shape SEC-08 actually asks about. Searches carrying no address have no entity and are found by
     * user and period instead.
     *
     * @param addressHash hash of the address that was searched for, or {@code null}
     * @param filter readable description of the filter, without the address itself
     */
    public void recordMessageSearch(Actor actor, AddressHash addressHash, String filter) {
        if (actor == null || actor.type() != ActorType.OPERATOR) {
            return;
        }
        audit.write(AuditEntry.changed(
                actor,
                ACTION_SEARCH,
                MESSAGE,
                addressHash == null ? null : addressHash.value(),
                AuditEntry.Change.of(null, filter),
                clock.now()));
    }
}
