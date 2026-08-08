package uz.hamkorbank.commhub.application.port.in.command;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Archiving DLQ entries, single or in bulk (FR-3.3).
 *
 * @param reason why nobody will retry these; kept in the audit journal, because "we decided not to
 *     resend four thousand messages" is exactly the decision that gets asked about later (FR-7.3)
 */
public record ArchiveDlqCommand(List<MessageId> messageIds, Actor actor, String reason) {

    public ArchiveDlqCommand {
        messageIds = Guard.copyOf(messageIds);
        Guard.isTrue(!messageIds.isEmpty(), "ArchiveDlqCommand.messageIds must not be empty");
        Guard.notNull(actor, "ArchiveDlqCommand.actor");
    }

    public static ArchiveDlqCommand of(MessageId messageId, Actor actor, String reason) {
        return new ArchiveDlqCommand(List.of(messageId), actor, reason);
    }
}
