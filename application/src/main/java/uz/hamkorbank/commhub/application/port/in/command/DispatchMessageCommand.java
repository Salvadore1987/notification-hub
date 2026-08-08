package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One turn of the sending saga for one message (AD-04).
 *
 * <p>Issued by the dispatcher of a traffic class, so that OTP and bulk traffic are driven by separate
 * pools and can never starve each other (TC-01).
 */
public record DispatchMessageCommand(MessageId messageId) {

    public DispatchMessageCommand {
        Guard.notNull(messageId, "DispatchMessageCommand.messageId");
    }

    public static DispatchMessageCommand of(MessageId messageId) {
        return new DispatchMessageCommand(messageId);
    }
}
