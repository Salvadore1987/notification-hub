package uz.hamkorbank.commhub.application.port.in.command;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Manual retry of DLQ entries, single or in bulk (FR-3.3).
 *
 * <p>An entry may be retried once: the message goes back to {@code QUEUED} and the lifecycle resumes
 * with a new delivery attempt (ST-02).
 */
public record ResendDlqCommand(List<MessageId> messageIds, Actor actor) {

    public ResendDlqCommand {
        messageIds = Guard.copyOf(messageIds);
        Guard.isTrue(!messageIds.isEmpty(), "ResendDlqCommand.messageIds must not be empty");
        Guard.notNull(actor, "ResendDlqCommand.actor");
    }

    public static ResendDlqCommand of(MessageId messageId, Actor actor) {
        return new ResendDlqCommand(List.of(messageId), actor);
    }
}
