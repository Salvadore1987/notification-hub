package uz.hamkorbank.commhub.application.service.support;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Writes the audit record of a configuration change (FR-7.3, SEC-08, AD-07).
 *
 * <p>Routing configuration is the part of the Hub where one edit changes where every message goes, so
 * every change to it is journalled with who made it and what the value was before — "the SMS traffic
 * moved to the reserve provider at 03:40" has to be answerable afterwards.
 *
 * <p>The record is written inside the same transaction as the change: an audited change that was rolled
 * back never happened, and a change that survived without its audit record is worse than either.
 */
@Component
public class ConfigAuditor {

    private final AuditPort audit;
    private final ClockPort clock;

    public ConfigAuditor(AuditPort audit, ClockPort clock) {
        this.audit = Guard.notNull(audit, "audit");
        this.clock = Guard.notNull(clock, "clock");
    }

    /**
     * Journals one change the operator was not asked to justify.
     *
     * @param before rendered state before the change; {@code null} for a creation
     * @param after rendered state after it; {@code null} for a deletion
     */
    public void record(Actor actor, String action, String entityType, String entityId, String before, String after) {
        record(actor, action, entityType, entityId, before, after, null);
    }

    /**
     * Journals one change together with the justification the operator gave for it (FR-7.3).
     *
     * <p>Used by the edits the panel asks a reason for — disabling a provider, closing a channel,
     * archiving a template, lifting a suppression. The reason is a field of the entry rather than a line
     * appended to the new state: an auditor filters and exports by it.
     *
     * <p>The two overloads differ in arity rather than in type on purpose: a {@code Change} parameter next
     * to a {@code String} one makes every {@code record(…, null, describe(x))} call ambiguous.
     */
    public void record(
            Actor actor,
            String action,
            String entityType,
            String entityId,
            String before,
            String after,
            String reason) {
        audit.write(new AuditEntry(
                actor, action, entityType, entityId, AuditEntry.Change.of(before, after), null, reason, clock.now()));
    }
}
