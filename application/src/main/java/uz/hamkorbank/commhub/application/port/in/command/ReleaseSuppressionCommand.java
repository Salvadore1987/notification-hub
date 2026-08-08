package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Removes an entry from the suppression list (FR-5.1).
 *
 * <p>By identifier rather than by address: releasing somebody is the operation that starts messages flowing
 * to a person again, and it should name the row an operator was looking at — not re-derive it from an address
 * typed a second time.
 *
 * @param reason why the ban is lifted; kept in the audit trail (FR-7.3)
 */
public record ReleaseSuppressionCommand(Actor actor, SuppressionEntryId entryId, String reason) {

    public ReleaseSuppressionCommand {
        Guard.notNull(actor, "ReleaseSuppressionCommand.actor");
        Guard.notNull(entryId, "ReleaseSuppressionCommand.entryId");
    }
}
